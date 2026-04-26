package com.syncforge.api.harness;

import java.util.Map;

public record ScriptedOperation(
        String clientKey,
        String operationId,
        long clientSeq,
        long baseRevision,
        String operationType,
        Map<String, Object> operation) {
}
