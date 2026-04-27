package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.syncforge.api.operation.application.CanonicalOperationPayloadHasher;
import com.syncforge.api.operation.application.OperationService;
import com.syncforge.api.operation.model.OperationSubmitResult;
import com.syncforge.api.operation.model.SubmitOperationCommand;
import org.springframework.beans.factory.annotation.Autowired;

abstract class TextConvergenceTestSupport extends AbstractIntegrationTest {
    @Autowired
    OperationService operationService;

    @Autowired
    CanonicalOperationPayloadHasher payloadHasher;

    OperationSubmitResult submitText(
            Fixture fixture,
            String operationId,
            long clientSeq,
            long baseRevision,
            String operationType,
            Map<String, Object> operation) {
        return submitText(fixture, fixture.editorId(), operationId, clientSeq, baseRevision, operationType, operation);
    }

    OperationSubmitResult submitText(
            Fixture fixture,
            UUID userId,
            String operationId,
            long clientSeq,
            long baseRevision,
            String operationType,
            Map<String, Object> operation) {
        return operationService.submit(new SubmitOperationCommand(
                fixture.roomId(),
                userId,
                "connection-" + operationId,
                "session-" + operationId,
                operationId,
                clientSeq,
                baseRevision,
                operationType,
                operation));
    }

    OperationSubmitResult submitAcceptedText(
            Fixture fixture,
            String operationId,
            long clientSeq,
            long baseRevision,
            String operationType,
            Map<String, Object> operation) {
        OperationSubmitResult result = submitText(fixture, operationId, clientSeq, baseRevision, operationType, operation);
        assertThat(result.accepted()).isTrue();
        return result;
    }

    OperationSubmitResult submitOfflineText(
            Fixture fixture,
            String operationId,
            String clientOperationId,
            long clientSeq,
            long baseRevision,
            long baseRoomSeq,
            String operationType,
            Map<String, Object> operation) {
        return operationService.submit(new SubmitOperationCommand(
                fixture.roomId(),
                fixture.editorId(),
                "connection-" + operationId,
                "session-" + operationId,
                operationId,
                clientSeq,
                baseRevision,
                operationType,
                operation,
                true,
                clientOperationId,
                baseRoomSeq,
                null,
                List.of(),
                payloadHasher.hash(operationType, operation)));
    }

    long outboxCount(UUID roomId) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from room_event_outbox where room_id = ?",
                Long.class,
                roomId);
        return count == null ? 0 : count;
    }

    long operationCount(UUID roomId) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from room_operations where room_id = ?",
                Long.class,
                roomId);
        return count == null ? 0 : count;
    }
}
