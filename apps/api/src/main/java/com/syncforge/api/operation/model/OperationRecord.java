package com.syncforge.api.operation.model;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record OperationRecord(
        UUID id,
        UUID roomId,
        UUID userId,
        String connectionId,
        String operationId,
        String clientSessionId,
        long clientSeq,
        long baseRevision,
        long roomSeq,
        long resultingRevision,
        String operationType,
        Map<String, Object> operation,
        OffsetDateTime createdAt
) {
}
