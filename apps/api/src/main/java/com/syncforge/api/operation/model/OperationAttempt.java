package com.syncforge.api.operation.model;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record OperationAttempt(
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
}
