package com.syncforge.api.operation.model;

import java.util.Map;
import java.util.UUID;

public record SubmitOperationCommand(
        UUID roomId,
        UUID userId,
        String connectionId,
        String clientSessionId,
        String operationId,
        Long clientSeq,
        Long baseRevision,
        String operationType,
        Map<String, Object> operation
) {
}
