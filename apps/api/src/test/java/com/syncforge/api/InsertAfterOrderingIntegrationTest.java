package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import com.syncforge.api.documentstate.application.DocumentStateService;
import com.syncforge.api.operation.application.CanonicalOperationPayloadHasher;
import com.syncforge.api.operation.application.OperationService;
import com.syncforge.api.operation.model.OperationSubmitResult;
import com.syncforge.api.operation.model.SubmitOperationCommand;
import com.syncforge.api.operation.store.OperationRepository;
import com.syncforge.api.text.model.TextAtom;
import com.syncforge.api.text.store.TextConvergenceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class InsertAfterOrderingIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    OperationService operationService;

    @Autowired
    DocumentStateService documentStateService;

    @Autowired
    TextConvergenceRepository textRepository;

    @Autowired
    OperationRepository operationRepository;

    @Autowired
    CanonicalOperationPayloadHasher payloadHasher;

    @Test
    void insertAfterStartCreatesStableAtomVisibleTextAndOutbox() {
        Fixture fixture = fixture();

        OperationSubmitResult result = submit(fixture, "insert-start", 1, 0,
                Map.of("anchorAtomId", "START", "text", "Hello"));

        assertThat(result.accepted()).isTrue();
        assertThat(documentStateService.getOrInitialize(fixture.roomId()).contentText()).isEqualTo("Hello");
        assertThat(textRepository.findAtom(fixture.roomId(), "insert-start:0")).isPresent();
        assertThat(outboxCount(fixture.roomId())).isEqualTo(1);
    }

    @Test
    void insertAfterExistingAtomUpdatesVisibleText() {
        Fixture fixture = fixture();
        submit(fixture, "insert-anchor", 1, 0, Map.of("text", "Hello"));

        OperationSubmitResult result = submit(fixture, "insert-after-anchor", 2, 1,
                Map.of("anchorAtomId", "insert-anchor:0", "text", " world"));

        assertThat(result.accepted()).isTrue();
        assertThat(documentStateService.getOrInitialize(fixture.roomId()).contentText()).isEqualTo("Hello world");
        assertThat(textRepository.findAtom(fixture.roomId(), "insert-after-anchor:0")).isPresent();
    }

    @Test
    void invalidAnchorRejectsWithoutMutationOutboxOrSequenceAdvance() {
        Fixture fixture = fixture();

        OperationSubmitResult result = submit(fixture, "bad-anchor", 1, 0,
                Map.of("anchorAtomId", "missing:0", "text", "nope"));

        assertThat(result.accepted()).isFalse();
        assertThat(result.code()).isEqualTo("INVALID_OPERATION_PAYLOAD");
        assertThat(result.message()).contains("anchorAtomId does not exist");
        assertThat(documentStateService.getOrInitialize(fixture.roomId()).contentText()).isEmpty();
        assertThat(textRepository.countRoomAtoms(fixture.roomId())).isZero();
        assertThat(outboxCount(fixture.roomId())).isZero();
        assertThat(currentRoomSeq(fixture.roomId())).isZero();
    }

    @Test
    void concurrentInsertsAfterSameAnchorUseRoomSeqOrderAndReplayMatchesLive() {
        Fixture fixture = fixture();
        submit(fixture, "anchor-a", 1, 0, Map.of("text", "A"));
        submit(fixture, "after-a-b", 2, 1, Map.of("anchorAtomId", "anchor-a:0", "text", "B"));
        submit(fixture, "after-a-c", 3, 2, Map.of("anchorAtomId", "anchor-a:0", "text", "C"));

        String live = documentStateService.getOrInitialize(fixture.roomId()).contentText();
        DocumentStateService.ReplayResult replay = documentStateService.replayOperations(
                operationRepository.findByRoom(fixture.roomId()), "");

        assertThat(live).isEqualTo("ABC");
        assertThat(replay.content()).isEqualTo(live);
        assertThat(textRepository.listRoomAtoms(fixture.roomId()))
                .extracting(TextAtom::atomId)
                .containsExactly("anchor-a:0", "after-a-b:0", "after-a-c:0");
    }

    @Test
    void offlineAcceptedInsertParticipatesInSameOrdering() {
        Fixture fixture = fixture();
        submit(fixture, "offline-anchor", 1, 0, Map.of("text", "A"));
        submitOffline(fixture, "offline-after-b", "client-op-b", 2, 1, 1,
                Map.of("anchorAtomId", "offline-anchor:0", "text", "B"));
        submit(fixture, "online-after-c", 3, 2, Map.of("anchorAtomId", "offline-anchor:0", "text", "C"));

        assertThat(documentStateService.getOrInitialize(fixture.roomId()).contentText()).isEqualTo("ABC");
        assertThat(documentStateService.verifyFullReplayEquivalence(fixture.roomId())).isTrue();
    }

    private OperationSubmitResult submit(Fixture fixture, String operationId, long clientSeq, long baseRevision,
            Map<String, Object> operation) {
        return operationService.submit(new SubmitOperationCommand(
                fixture.roomId(),
                fixture.editorId(),
                "connection-" + operationId,
                "session-" + operationId,
                operationId,
                clientSeq,
                baseRevision,
                "TEXT_INSERT_AFTER",
                operation));
    }

    private OperationSubmitResult submitOffline(Fixture fixture, String operationId, String clientOperationId,
            long clientSeq, long baseRevision, long baseRoomSeq, Map<String, Object> operation) {
        return operationService.submit(new SubmitOperationCommand(
                fixture.roomId(),
                fixture.editorId(),
                "connection-" + operationId,
                "session-" + operationId,
                operationId,
                clientSeq,
                baseRevision,
                "TEXT_INSERT_AFTER",
                operation,
                true,
                clientOperationId,
                baseRoomSeq,
                null,
                java.util.List.of(),
                payloadHasher.hash("TEXT_INSERT_AFTER", operation)));
    }

    private long outboxCount(UUID roomId) {
        Long count = jdbcTemplate.queryForObject("select count(*) from room_event_outbox where room_id = ?",
                Long.class, roomId);
        return count == null ? 0 : count;
    }

    private long currentRoomSeq(UUID roomId) {
        return jdbcTemplate.query("""
                select current_room_seq
                from room_sequence_counters
                where room_id = ?
                """, (rs, rowNum) -> rs.getLong("current_room_seq"), roomId).stream().findFirst().orElse(0L);
    }
}
