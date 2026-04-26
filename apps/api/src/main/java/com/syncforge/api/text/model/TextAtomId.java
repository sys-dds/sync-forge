package com.syncforge.api.text.model;

public record TextAtomId(String value) {
    public TextAtomId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("atomId is required");
        }
    }

    public static TextAtomId fromOperation(String operationId, int spanIndex) {
        if (operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException("operationId is required");
        }
        if (spanIndex < 0) {
            throw new IllegalArgumentException("spanIndex must be non-negative");
        }
        return new TextAtomId(operationId + ":" + spanIndex);
    }
}
