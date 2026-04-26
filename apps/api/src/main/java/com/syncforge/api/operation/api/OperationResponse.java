package com.syncforge.api.operation.api;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import com.syncforge.api.operation.model.OperationRecord;

public record OperationResponse(
        UUID id,
        UUID roomId,
        UUID userId,
        String connectionId,
        String operationId,
        String clientSessionId,
        long clientSeq,
        long baseRevision,
        long roomSeq,
        long revision,
        String operationType,
        Map<String, Object> operation,
        OffsetDateTime createdAt
) {
    public static OperationResponse from(OperationRecord record) {
        return new OperationResponse(
                record.id(),
                record.roomId(),
                record.userId(),
                record.connectionId(),
                record.operationId(),
                record.clientSessionId(),
                record.clientSeq(),
                record.baseRevision(),
                record.roomSeq(),
                record.resultingRevision(),
                record.operationType(),
                record.operation(),
                record.createdAt());
    }
}
