package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.syncforge.api.documentstate.application.DocumentStateService;
import com.syncforge.api.operation.application.OperationService;
import com.syncforge.api.operation.model.OperationSubmitResult;
import com.syncforge.api.operation.model.SubmitOperationCommand;
import com.syncforge.api.text.store.TextConvergenceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class TextOperationIdempotencyIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    OperationService operationService;

    @Autowired
    DocumentStateService documentStateService;

    @Autowired
    TextConvergenceRepository textRepository;

    @Test
    void duplicateInsertRetryDoesNotDoubleTextOrOutbox() {
        Fixture fixture = fixture();
        OperationSubmitResult first = submit(fixture, "text-idem-insert", 1, 0,
                "TEXT_INSERT_AFTER", Map.of("text", "A"));
        OperationSubmitResult duplicate = submit(fixture, "text-idem-insert", 2, 0,
                "TEXT_INSERT_AFTER", Map.of("text", "A"));

        assertThat(first.accepted()).isTrue();
        assertThat(duplicate.duplicate()).isTrue();
        assertThat(documentStateService.getOrInitialize(fixture.roomId()).contentText()).isEqualTo("A");
        assertThat(textRepository.countRoomAtoms(fixture.roomId())).isEqualTo(1);
        assertThat(outboxCount(fixture.roomId())).isEqualTo(1);
    }

    @Test
    void duplicateDeleteRetryDoesNotCorruptTextOrOutbox() {
        Fixture fixture = fixture();
        submit(fixture, "text-idem-delete-insert", 1, 0, "TEXT_INSERT_AFTER", Map.of("text", "A"));
        submit(fixture, "text-idem-delete", 2, 1, "TEXT_DELETE_ATOMS",
                Map.of("atomIds", List.of("text-idem-delete-insert:0")));
        OperationSubmitResult duplicate = submit(fixture, "text-idem-delete", 3, 1, "TEXT_DELETE_ATOMS",
                Map.of("atomIds", List.of("text-idem-delete-insert:0")));

        assertThat(duplicate.duplicate()).isTrue();
        assertThat(documentStateService.getOrInitialize(fixture.roomId()).contentText()).isEmpty();
        assertThat(outboxCount(fixture.roomId())).isEqualTo(2);
    }

    @Test
    void duplicatePayloadConflictRemainsSafe() {
        Fixture fixture = fixture();
        submit(fixture, "text-idem-conflict", 1, 0, "TEXT_INSERT_AFTER", Map.of("text", "A"));
        OperationSubmitResult conflict = submit(fixture, "text-idem-conflict", 2, 0,
                "TEXT_INSERT_AFTER", Map.of("text", "B"));

        assertThat(conflict.accepted()).isFalse();
        assertThat(conflict.code()).isEqualTo("DUPLICATE_OPERATION_CONFLICT");
        assertThat(documentStateService.getOrInitialize(fixture.roomId()).contentText()).isEqualTo("A");
        assertThat(textRepository.countRoomAtoms(fixture.roomId())).isEqualTo(1);
        assertThat(outboxCount(fixture.roomId())).isEqualTo(1);
    }

    private OperationSubmitResult submit(Fixture fixture, String operationId, long clientSeq, long baseRevision,
            String operationType, Map<String, Object> operation) {
        return operationService.submit(new SubmitOperationCommand(
                fixture.roomId(), fixture.editorId(), "connection-" + operationId, "session-" + operationId,
                operationId, clientSeq, baseRevision, operationType, operation));
    }

    private long outboxCount(UUID roomId) {
        Long count = jdbcTemplate.queryForObject("select count(*) from room_event_outbox where room_id = ?",
                Long.class, roomId);
        return count == null ? 0 : count;
    }
}
