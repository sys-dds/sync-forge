package com.syncforge.api.documentstate.model;

import java.util.Map;

public record AppliedTextOperation(
        String operationType,
        Map<String, Object> operation,
        String content
) {
}
