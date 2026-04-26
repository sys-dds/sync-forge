package com.syncforge.api.operation.api;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import com.syncforge.api.operation.model.OperationAttempt;

public record OperationAttemptResponse(
        UUID id,
        UUID roomId,
        UUID userId,
        String connectionId,
        String operationId,
        long clientSeq,
        long baseRevision,
        String operationType,
        Map<String, Object> operation,
        String outcome,
        String nackCode,
        String message,
        Long assignedRoomSeq,
        Long resultingRevision,
        UUID duplicateOfOperationId,
        OffsetDateTime createdAt
) {
    public static OperationAttemptResponse from(OperationAttempt attempt) {
        return new OperationAttemptResponse(
                attempt.id(),
                attempt.roomId(),
                attempt.userId(),
                attempt.connectionId(),
                attempt.operationId(),
                attempt.clientSeq(),
                attempt.baseRevision(),
                attempt.operationType(),
                attempt.operation(),
                attempt.outcome(),
                attempt.nackCode(),
                attempt.message(),
                attempt.assignedRoomSeq(),
                attempt.resultingRevision(),
                attempt.duplicateOfOperationId(),
                attempt.createdAt());
    }
}
