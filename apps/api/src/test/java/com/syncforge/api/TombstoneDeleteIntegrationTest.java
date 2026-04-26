package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.syncforge.api.documentstate.application.DocumentStateService;
import com.syncforge.api.operation.application.OperationService;
import com.syncforge.api.operation.model.OperationSubmitResult;
import com.syncforge.api.operation.model.SubmitOperationCommand;
import com.syncforge.api.text.model.TextAtom;
import com.syncforge.api.text.store.TextConvergenceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class TombstoneDeleteIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    OperationService operationService;

    @Autowired
    DocumentStateService documentStateService;

    @Autowired
    TextConvergenceRepository textRepository;

    @Test
    void deleteTombstonesAtomAndHidesText() {
        Fixture fixture = fixture();
        submitInsert(fixture, "insert-a", 1, 0, Map.of("text", "A"));
        submitInsert(fixture, "insert-b", 2, 1, Map.of("anchorAtomId", "insert-a:0", "text", "B"));

        OperationSubmitResult delete = submitDelete(fixture, "delete-a", 3, 2, List.of("insert-a:0"));

        assertThat(delete.accepted()).isTrue();
        assertThat(documentStateService.getOrInitialize(fixture.roomId()).contentText()).isEqualTo("B");
        TextAtom atom = textRepository.findAtom(fixture.roomId(), "insert-a:0").orElseThrow();
        assertThat(atom.tombstoned()).isTrue();
        assertThat(atom.deletedByOperationId()).isEqualTo("delete-a");
        assertThat(atom.deletedAtRoomSeq()).isEqualTo(3);
        assertThat(outboxCount(fixture.roomId())).isEqualTo(3);
    }

    @Test
    void duplicateDeleteRetryDoesNotCorruptVisibleText() {
        Fixture fixture = fixture();
        submitInsert(fixture, "dup-delete-insert", 1, 0, Map.of("text", "A"));
        OperationSubmitResult first = submitDelete(fixture, "dup-delete", 2, 1, List.of("dup-delete-insert:0"));
        OperationSubmitResult duplicate = submitDelete(fixture, "dup-delete", 99, 1, List.of("dup-delete-insert:0"));

        assertThat(first.accepted()).isTrue();
        assertThat(duplicate.accepted()).isTrue();
        assertThat(duplicate.duplicate()).isTrue();
        assertThat(documentStateService.getOrInitialize(fixture.roomId()).contentText()).isEmpty();
        assertThat(textRepository.countRoomAtoms(fixture.roomId())).isEqualTo(1);
    }

    @Test
    void alreadyTombstonedTargetIsSafe() {
        Fixture fixture = fixture();
        submitInsert(fixture, "already-tombstone-insert", 1, 0, Map.of("text", "A"));
        submitDelete(fixture, "already-tombstone-delete-1", 2, 1, List.of("already-tombstone-insert:0"));

        OperationSubmitResult second = submitDelete(fixture, "already-tombstone-delete-2", 3, 2,
                List.of("already-tombstone-insert:0"));

        assertThat(second.accepted()).isTrue();
        assertThat(documentStateService.getOrInitialize(fixture.roomId()).contentText()).isEmpty();
        assertThat(currentRoomSeq(fixture.roomId())).isEqualTo(3);
    }

    @Test
    void unknownDeleteTargetRejectsWithoutMutationOutboxOrSequenceAdvance() {
        Fixture fixture = fixture();

        OperationSubmitResult delete = submitDelete(fixture, "delete-missing", 1, 0, List.of("missing:0"));

        assertThat(delete.accepted()).isFalse();
        assertThat(delete.code()).isEqualTo("INVALID_OPERATION_PAYLOAD");
        assertThat(delete.message()).contains("atomId does not exist");
        assertThat(textRepository.countRoomAtoms(fixture.roomId())).isZero();
        assertThat(outboxCount(fixture.roomId())).isZero();
        assertThat(currentRoomSeq(fixture.roomId())).isZero();
    }

    private OperationSubmitResult submitInsert(Fixture fixture, String operationId, long clientSeq, long baseRevision,
            Map<String, Object> operation) {
        return submit(fixture, operationId, clientSeq, baseRevision, "TEXT_INSERT_AFTER", operation);
    }

    private OperationSubmitResult submitDelete(Fixture fixture, String operationId, long clientSeq, long baseRevision,
            List<String> atomIds) {
        return submit(fixture, operationId, clientSeq, baseRevision, "TEXT_DELETE_ATOMS", Map.of("atomIds", atomIds));
    }

    private OperationSubmitResult submit(Fixture fixture, String operationId, long clientSeq, long baseRevision,
            String operationType, Map<String, Object> operation) {
        return operationService.submit(new SubmitOperationCommand(
                fixture.roomId(),
                fixture.editorId(),
                "connection-" + operationId,
                "session-" + operationId,
                operationId,
                clientSeq,
                baseRevision,
                operationType,
                operation));
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
