package com.syncforge.api.operation.model;

import java.util.Map;

public record OperationSubmitResult(
        boolean accepted,
        boolean duplicate,
        String operationId,
        Long clientSeq,
        Long roomSeq,
        Long revision,
        String code,
        String message,
        Long currentRevision,
        String operationType,
        Map<String, Object> operation,
        boolean transformed
) {
    public static OperationSubmitResult ack(
            String operationId,
            long clientSeq,
            long roomSeq,
            long revision,
            boolean duplicate,
            String operationType,
            Map<String, Object> operation) {
        return ack(operationId, clientSeq, roomSeq, revision, duplicate, operationType, operation, false);
    }

    public static OperationSubmitResult ack(
            String operationId,
            long clientSeq,
            long roomSeq,
            long revision,
            boolean duplicate,
            String operationType,
            Map<String, Object> operation,
            boolean transformed) {
        return new OperationSubmitResult(true, duplicate, operationId, clientSeq, roomSeq, revision, null, null, null,
                operationType, operation, transformed);
    }

    public static OperationSubmitResult nack(
            String operationId,
            Long clientSeq,
            String code,
            String message,
            Long currentRevision) {
        return new OperationSubmitResult(false, false, operationId, clientSeq, null, null, code, message, currentRevision,
                null, Map.of(), false);
    }
}
