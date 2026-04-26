package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncforge.api.documentstate.application.DocumentStateService;
import com.syncforge.api.operation.application.OperationService;
import com.syncforge.api.operation.model.OperationSubmitResult;
import com.syncforge.api.operation.model.SubmitOperationCommand;
import com.syncforge.api.operation.store.OperationRepository;
import com.syncforge.api.resume.application.RoomBackfillService;
import com.syncforge.api.resume.model.BackfillResult;
import com.syncforge.api.resume.store.ClientOffsetRepository;
import com.syncforge.api.shared.ForbiddenException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "syncforge.resume.max-backfill-events=1")
class ClientAckBackfillConsistencyIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    OperationService operationService;

    @Autowired
    OperationRepository operationRepository;

    @Autowired
    DocumentStateService documentStateService;

    @Autowired
    RoomBackfillService roomBackfillService;

    @Autowired
    ClientOffsetRepository clientOffsetRepository;

    @Test
    void ackRecordsProgressButCannotSkipAheadOfCanonicalOperationTruth() throws Exception {
        Fixture fixture = fixture();
        submitInsert(fixture, "ack-truth-1", 1, 0, 0, "a");

        TestSocket viewer = TestSocket.connect(websocketUri(), fixture.viewerId(), "ack-device", "ack-session", objectMapper);
        join(viewer, fixture.roomId().toString());

        viewer.send(ack(fixture.roomId().toString(), "ack-1", 1));
        assertThat(payload(viewer.nextOfType("ROOM_EVENT_ACKED"))).containsEntry("roomSeq", 1);
        assertThat(clientOffsetRepository.find(fixture.roomId(), fixture.viewerId(), "ack-session")).contains(1L);

        viewer.send(ack(fixture.roomId().toString(), "ack-ahead", 2));
        Map<String, Object> error = payload(viewer.nextOfType("ERROR"));

        assertThat(error).containsEntry("code", "ACK_BEYOND_CANONICAL_ROOM_SEQ");
        assertThat(clientOffsetRepository.find(fixture.roomId(), fixture.viewerId(), "ack-session")).contains(1L);
        assertThat(operationRepository.findByRoom(fixture.roomId())).hasSize(1);
        assertThat(documentStateService.getOrInitialize(fixture.roomId()).contentText()).isEqualTo("a");

        viewer.close();
    }

    @Test
    void backfillUsesCanonicalDbTruthEvenWhenRedisStreamDeliveryHasNotHappened() {
        Fixture fixture = fixture();
        OperationSubmitResult result = submitInsert(fixture, "db-backfill-1", 1, 0, 0, "x");

        BackfillResult backfill = roomBackfillService.backfill(fixture.roomId(), fixture.viewerId(), "db-backfill-session", 0);

        assertThat(result.accepted()).isTrue();
        assertThat(backfill.outcome()).isEqualTo("BACKFILLED");
        assertThat(backfill.events()).hasSize(1);
        assertThat(backfill.events()).extracting(event -> event.get("roomSeq")).containsExactly(1L);
        assertThat(backfill.events()).extracting(event -> event.get("operationId")).containsExactly("db-backfill-1");
        assertThat(countOutboxPublished(fixture.roomId())).isZero();
    }

    @Test
    void backfillIsOrderedAndResyncPolicyIsPreserved() {
        Fixture fixture = fixture();
        submitInsert(fixture, "ordered-1", 1, 0, 0, "a");
        submitInsert(fixture, "ordered-2", 2, 1, 1, "b");

        BackfillResult ordered = roomBackfillService.backfill(fixture.roomId(), fixture.viewerId(), "ordered-session", 1);
        assertThat(ordered.outcome()).isEqualTo("BACKFILLED");
        assertThat(ordered.events()).extracting(event -> event.get("roomSeq")).containsExactly(2L);

        BackfillResult tooFarBehind = roomBackfillService.backfill(fixture.roomId(), fixture.viewerId(), "resync-session", 0);
        assertThat(tooFarBehind.outcome()).isEqualTo("RESYNC_REQUIRED");
        assertThat(tooFarBehind.events()).isEmpty();
        assertThat(tooFarBehind.currentState()).isNotNull();
        assertThat(tooFarBehind.currentState().contentText()).isEqualTo("ab");
        assertThat(tooFarBehind.currentState().currentRoomSeq()).isEqualTo(2);
    }

    @Test
    void permissionRemovalStillBlocksBackfillWithoutProtectedPayload() {
        Fixture fixture = fixture();
        submitInsert(fixture, "removed-backfill-1", 1, 0, 0, "secret");

        jdbcTemplate.update("""
                update room_memberships
                set status = 'REMOVED'
                where room_id = ? and user_id = ?
                """, fixture.roomId(), fixture.viewerId());

        assertThatThrownBy(() -> roomBackfillService.backfill(fixture.roomId(), fixture.viewerId(), "removed-session", 0))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("view room")
                .hasMessageNotContaining("secret")
                .hasMessageNotContaining("removed-backfill-1");
    }

    private OperationSubmitResult submitInsert(
            Fixture fixture,
            String operationId,
            long clientSeq,
            long baseRevision,
            int position,
            String text) {
        return operationService.submit(new SubmitOperationCommand(
                fixture.roomId(),
                fixture.editorId(),
                "ack-backfill-connection",
                "ack-backfill-session",
                operationId,
                clientSeq,
                baseRevision,
                "TEXT_INSERT",
                Map.of("position", position, "text", text)));
    }

    private void join(TestSocket socket, String roomId) throws Exception {
        socket.send(Map.of("type", "JOIN_ROOM", "messageId", "join", "roomId", roomId, "payload", Map.of()));
        socket.nextOfType("JOINED_ROOM");
    }

    private Map<String, Object> ack(String roomId, String messageId, long roomSeq) {
        return Map.of("type", "ACK_ROOM_EVENT", "messageId", messageId, "roomId", roomId,
                "payload", Map.of("roomSeq", roomSeq));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("payload");
    }

    private int countOutboxPublished(java.util.UUID roomId) {
        return jdbcTemplate.queryForObject("""
                select count(*)
                from room_event_outbox
                where room_id = ? and status = 'PUBLISHED'
                """, Integer.class, roomId);
    }
}
