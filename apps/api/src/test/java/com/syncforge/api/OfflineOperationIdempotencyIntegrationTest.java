package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import com.syncforge.api.delivery.RoomEventOutboxRepository;
import com.syncforge.api.operation.application.CanonicalOperationPayloadHasher;
import com.syncforge.api.operation.application.OperationService;
import com.syncforge.api.operation.model.OperationSubmitResult;
import com.syncforge.api.operation.model.SubmitOperationCommand;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class OfflineOperationIdempotencyIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    OperationService operationService;

    @Autowired
    CanonicalOperationPayloadHasher payloadHasher;

    @Autowired
    RoomEventOutboxRepository outboxRepository;

    @Test
    void duplicateOfflineRetryReturnsSameOutcomeWithoutSecondOperationOrOutbox() {
        Fixture fixture = fixture();
        Map<String, Object> operation = Map.of("position", 0, "text", "offline");

        OperationSubmitResult first = submitOffline(fixture, "offline-op-1", "client-op-1", 1, 0, 0, operation);
        OperationSubmitResult retry = submitOffline(fixture, "offline-op-1-retry", "client-op-1", 2, 0, 0, operation);

        assertThat(first.accepted()).isTrue();
        assertThat(retry.accepted()).isTrue();
        assertThat(retry.duplicate()).isTrue();
        assertThat(retry.roomSeq()).isEqualTo(first.roomSeq());
        assertThat(retry.revision()).isEqualTo(first.revision());
        assertThat(operationRows(fixture)).isEqualTo(1);
        assertThat(outboxRows(fixture)).isEqualTo(1);
        assertThat(outboxRepository.findByRoomSeq(fixture.roomId(), first.roomSeq())).isPresent();
    }

    @Test
    void duplicateOfflineRetryWithDifferentPayloadReturnsConflictWithoutMutationOrOutbox() {
        Fixture fixture = fixture();
        OperationSubmitResult first = submitOffline(
                fixture,
                "offline-op-2",
                "client-op-2",
                1,
                0,
                0,
                Map.of("position", 0, "text", "a"));

        OperationSubmitResult conflict = submitOffline(
                fixture,
                "offline-op-2-conflict",
                "client-op-2",
                2,
                0,
                0,
                Map.of("position", 0, "text", "b"));

        assertThat(first.accepted()).isTrue();
        assertThat(conflict.accepted()).isFalse();
        assertThat(conflict.code()).isEqualTo("OFFLINE_CLIENT_OPERATION_CONFLICT");
        assertThat(operationRows(fixture)).isEqualTo(1);
        assertThat(outboxRows(fixture)).isEqualTo(1);
    }

    private OperationSubmitResult submitOffline(
            Fixture fixture,
            String operationId,
            String clientOperationId,
            long clientSeq,
            long baseRoomSeq,
            long baseRevision,
            Map<String, Object> operation) {
        return operationService.submit(new SubmitOperationCommand(
                fixture.roomId(),
                fixture.editorId(),
                "offline-idempotency-connection",
                "offline-idempotency-session",
                operationId,
                clientSeq,
                baseRevision,
                "TEXT_INSERT",
                operation,
                true,
                clientOperationId,
                baseRoomSeq,
                null,
                java.util.List.of(),
                payloadHasher.hash("TEXT_INSERT", operation)));
    }

    private long operationRows(Fixture fixture) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from room_operations where room_id = ?",
                Long.class,
                fixture.roomId());
        return count == null ? 0L : count;
    }

    private long outboxRows(Fixture fixture) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from room_event_outbox where room_id = ?",
                Long.class,
                fixture.roomId());
        return count == null ? 0L : count;
    }
}
