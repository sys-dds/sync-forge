package com.syncforge.api.operation.model;

import java.util.List;
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
        Map<String, Object> operation,
        Boolean offline,
        String clientOperationId,
        Long baseRoomSeq,
        Long dependsOnRoomSeq,
        List<String> dependsOnOperationIds,
        String canonicalPayloadHash
) {
    public SubmitOperationCommand(
            UUID roomId,
            UUID userId,
            String connectionId,
            String clientSessionId,
            String operationId,
            Long clientSeq,
            Long baseRevision,
            String operationType,
            Map<String, Object> operation) {
        this(roomId, userId, connectionId, clientSessionId, operationId, clientSeq, baseRevision, operationType,
                operation, false, null, null, null, List.of(), null);
    }

    public SubmitOperationCommand {
        dependsOnOperationIds = dependsOnOperationIds == null ? List.of() : List.copyOf(dependsOnOperationIds);
    }

    public boolean offlineRequested() {
        return Boolean.TRUE.equals(offline);
    }
}
