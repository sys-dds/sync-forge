package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import com.syncforge.api.documentstate.application.DocumentStateService;
import com.syncforge.api.operation.application.OperationService;
import com.syncforge.api.operation.model.OperationSubmitResult;
import com.syncforge.api.operation.model.SubmitOperationCommand;
import com.syncforge.api.operation.store.OperationRepository;
import com.syncforge.api.resume.application.ClientOffsetService;
import com.syncforge.api.resume.application.RoomBackfillService;
import com.syncforge.api.resume.model.BackfillResult;
import com.syncforge.api.resume.store.RoomBackfillRepository;
import com.syncforge.api.room.application.RoomPermissionService;
import com.syncforge.api.shared.ForbiddenException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ReconnectBackfillReplayIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    OperationService operationService;

    @Autowired
    OperationRepository operationRepository;

    @Autowired
    ClientOffsetService clientOffsetService;

    @Autowired
    RoomBackfillService roomBackfillService;

    @Autowired
    RoomBackfillRepository roomBackfillRepository;

    @Autowired
    DocumentStateService documentStateService;

    @Autowired
    RoomPermissionService permissionService;

    @Test
    void reconnectBackfillsDbTruthInOrderRecordsAckProgressAndAllowsSubmitAfterCatchup() {
        Fixture fixture = fixture();
        submit(fixture, fixture.ownerId(), "reconnect-owner-1", 1, 0, Map.of("position", 0, "text", "a"));
        submit(fixture, fixture.ownerId(), "reconnect-owner-2", 2, 1, Map.of("position", 1, "text", "b"));

        String reconnectSession = "reconnect-editor-session";
        assertThat(clientOffsetService.acknowledge(fixture.roomId(), fixture.editorId(), reconnectSession, 0)).isTrue();
        long lastSeen = clientOffsetService.lastSeenOrDefault(fixture.roomId(), fixture.editorId(), reconnectSession, -1);
        BackfillResult backfill = roomBackfillService.backfill(fixture.roomId(), fixture.editorId(), reconnectSession, lastSeen);

        assertThat(backfill.outcome()).isEqualTo("BACKFILLED");
        assertThat(backfill.events()).extracting(event -> event.get("roomSeq")).containsExactly(1L, 2L);
        assertThat(backfill.events()).extracting(event -> event.get("operationId"))
                .containsExactly("reconnect-owner-1", "reconnect-owner-2");

        assertThat(clientOffsetService.acknowledge(fixture.roomId(), fixture.editorId(), reconnectSession, backfill.toRoomSeq())).isTrue();
        assertThat(clientOffsetService.lastSeenOrDefault(fixture.roomId(), fixture.editorId(), reconnectSession, 0)).isEqualTo(2);
        assertThat(roomBackfillService.backfill(fixture.roomId(), fixture.editorId(), reconnectSession, 2).events()).isEmpty();

        BackfillResult repeatedReplay = roomBackfillService.backfill(fixture.roomId(), fixture.editorId(), "repeat-replay", 0);
        assertThat(repeatedReplay.events()).extracting(event -> event.get("roomSeq")).containsExactly(1L, 2L);
        assertThat(repeatedReplay.events()).extracting(event -> event.get("operationId"))
                .containsExactly("reconnect-owner-1", "reconnect-owner-2");

        OperationSubmitResult afterCatchup = submit(fixture, fixture.editorId(), "reconnect-editor-after-catchup", 1, 2,
                Map.of("position", 2, "text", "c"));
        assertThat(afterCatchup.accepted()).isTrue();
        assertThat(documentStateService.getOrInitialize(fixture.roomId()).contentText()).isEqualTo("abc");
        assertThat(documentStateService.rebuildFromOperationLog(fixture.roomId()).state().contentText()).isEqualTo("abc");
    }

    @Test
    void tooFarBehindStillReturnsResyncRequiredAndPermissionRemovalStillBlocksBackfill() {
        Fixture fixture = fixture();
        submit(fixture, fixture.ownerId(), "resync-owner-1", 1, 0, Map.of("position", 0, "text", "a"));
        submit(fixture, fixture.ownerId(), "resync-owner-2", 2, 1, Map.of("position", 1, "text", "b"));

        RoomBackfillService tinyBackfill = new RoomBackfillService(
                operationRepository,
                roomBackfillRepository,
                documentStateService,
                permissionService,
                1);
        BackfillResult resync = tinyBackfill.backfill(fixture.roomId(), fixture.editorId(), "too-far-session", 0);

        assertThat(resync.outcome()).isEqualTo("RESYNC_REQUIRED");
        assertThat(resync.currentState()).isNotNull();
        assertThat(resync.currentState().contentText()).isEqualTo("ab");

        removeMember(fixture.roomId(), fixture.editorId());
        assertThatThrownBy(() -> roomBackfillService.backfill(fixture.roomId(), fixture.editorId(), "removed-session", 0))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("User is not allowed to view room");
    }

    private OperationSubmitResult submit(
            Fixture fixture,
            java.util.UUID userId,
            String operationId,
            long clientSeq,
            long baseRevision,
            Map<String, Object> operation) {
        return operationService.submit(new SubmitOperationCommand(
                fixture.roomId(),
                userId,
                "reconnect-connection-" + userId,
                "reconnect-session-" + userId,
                operationId,
                clientSeq,
                baseRevision,
                "TEXT_INSERT",
                operation));
    }

    private void removeMember(java.util.UUID roomId, java.util.UUID userId) {
        jdbcTemplate.update("""
                update room_memberships
                set status = 'REMOVED'
                where room_id = ? and user_id = ?
                """, roomId, userId);
    }
}
