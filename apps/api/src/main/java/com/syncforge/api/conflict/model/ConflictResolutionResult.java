package com.syncforge.api.conflict.model;

import java.util.List;
import java.util.Map;

import com.syncforge.api.operation.model.OperationRecord;

public record ConflictResolutionResult(
        boolean accepted,
        String decision,
        String reason,
        String code,
        String operationType,
        Map<String, Object> operation,
        List<OperationRecord> concurrentOperations,
        boolean transformed
) {
    public static ConflictResolutionResult transformed(
            String decision,
            String reason,
            String operationType,
            Map<String, Object> operation,
            List<OperationRecord> concurrentOperations,
            boolean transformed) {
        return new ConflictResolutionResult(true, decision, reason, null, operationType, operation, concurrentOperations, transformed);
    }

    public static ConflictResolutionResult rejected(
            String reason,
            List<OperationRecord> concurrentOperations) {
        return new ConflictResolutionResult(false, "REJECTED_REQUIRES_RESYNC", reason, "CONFLICT_REQUIRES_RESYNC",
                null, Map.of(), concurrentOperations, false);
    }
}
