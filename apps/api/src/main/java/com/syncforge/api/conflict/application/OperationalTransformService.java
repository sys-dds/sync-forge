package com.syncforge.api.conflict.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.syncforge.api.conflict.model.ConflictResolutionResult;
import com.syncforge.api.operation.model.OperationRecord;
import com.syncforge.api.operation.model.SubmitOperationCommand;
import org.springframework.stereotype.Service;

@Service
public class OperationalTransformService {
    public ConflictResolutionResult transform(SubmitOperationCommand command, List<OperationRecord> concurrentOperations) {
        String type = command.operationType();
        Map<String, Object> operation = new LinkedHashMap<>(command.operation());
        boolean transformed = false;

        for (OperationRecord existing : concurrentOperations) {
            TransformStep step = transformOne(type, operation, existing, command);
            if (!step.accepted()) {
                return ConflictResolutionResult.rejected(step.reason(), concurrentOperations);
            }
            type = step.operationType();
            operation = step.operation();
            transformed = transformed || step.transformed();
        }

        String decision = "NOOP".equals(type) && !"NOOP".equals(command.operationType())
                ? "NOOP_AFTER_TRANSFORM"
                : "TRANSFORMED_APPLY";
        String reason = transformed ? "stale operation transformed against concurrent room operations" : "stale operation remained valid";
        return ConflictResolutionResult.transformed(decision, reason, type, operation, concurrentOperations, transformed);
    }

    private TransformStep transformOne(String incomingType, Map<String, Object> incoming, OperationRecord existing, SubmitOperationCommand command) {
        if ("NOOP".equals(incomingType) || "NOOP".equals(existing.operationType())) {
            return TransformStep.accepted(incomingType, incoming, false);
        }
        if ("TEXT_REPLACE".equals(incomingType) || "TEXT_REPLACE".equals(existing.operationType())) {
            return TransformStep.rejected("replace conflict requires resync");
        }
        if ("TEXT_INSERT".equals(incomingType) && "TEXT_INSERT".equals(existing.operationType())) {
            return insertVsInsert(incoming, existing, command);
        }
        if ("TEXT_INSERT".equals(incomingType) && "TEXT_DELETE".equals(existing.operationType())) {
            return insertVsDelete(incoming, existing);
        }
        if ("TEXT_DELETE".equals(incomingType) && "TEXT_INSERT".equals(existing.operationType())) {
            return deleteVsInsert(incoming, existing);
        }
        if ("TEXT_DELETE".equals(incomingType) && "TEXT_DELETE".equals(existing.operationType())) {
            return deleteVsDelete(incoming, existing);
        }
        return TransformStep.rejected("unsupported transform combination");
    }

    private TransformStep insertVsInsert(Map<String, Object> incoming, OperationRecord existing, SubmitOperationCommand command) {
        int position = intValue(incoming, "position");
        int existingPosition = intValue(existing.operation(), "position");
        String existingText = stringValue(existing.operation(), "text");
        boolean existingWinsTie = existing.operationId().compareTo(command.operationId()) <= 0
                || existing.userId().toString().compareTo(command.userId().toString()) <= 0;
        if (existingPosition < position || (existingPosition == position && existingWinsTie)) {
            return TransformStep.accepted("TEXT_INSERT",
                    Map.of("position", position + existingText.length(), "text", stringValue(incoming, "text")), true);
        }
        return TransformStep.accepted("TEXT_INSERT", incoming, false);
    }

    private TransformStep insertVsDelete(Map<String, Object> incoming, OperationRecord existing) {
        int position = intValue(incoming, "position");
        int deleteStart = intValue(existing.operation(), "position");
        int deleteLength = intValue(existing.operation(), "length");
        int deleteEnd = deleteStart + deleteLength;
        int transformedPosition = position;
        if (position > deleteEnd) {
            transformedPosition = position - deleteLength;
        } else if (position >= deleteStart) {
            transformedPosition = deleteStart;
        }
        return TransformStep.accepted("TEXT_INSERT",
                Map.of("position", transformedPosition, "text", stringValue(incoming, "text")),
                transformedPosition != position);
    }

    private TransformStep deleteVsInsert(Map<String, Object> incoming, OperationRecord existing) {
        int position = intValue(incoming, "position");
        int length = intValue(incoming, "length");
        int insertPosition = intValue(existing.operation(), "position");
        int insertLength = stringValue(existing.operation(), "text").length();
        int end = position + length;
        if (insertPosition <= position) {
            return TransformStep.accepted("TEXT_DELETE", Map.of("position", position + insertLength, "length", length), true);
        }
        if (insertPosition < end) {
            return TransformStep.accepted("TEXT_DELETE", Map.of("position", position, "length", length + insertLength), true);
        }
        return TransformStep.accepted("TEXT_DELETE", incoming, false);
    }

    private TransformStep deleteVsDelete(Map<String, Object> incoming, OperationRecord existing) {
        int position = intValue(incoming, "position");
        int length = intValue(incoming, "length");
        int end = position + length;
        int existingPosition = intValue(existing.operation(), "position");
        int existingLength = intValue(existing.operation(), "length");
        int existingEnd = existingPosition + existingLength;
        if (existingEnd <= position) {
            return TransformStep.accepted("TEXT_DELETE", Map.of("position", position - existingLength, "length", length), true);
        }
        if (existingPosition >= end) {
            return TransformStep.accepted("TEXT_DELETE", incoming, false);
        }

        int overlapStart = Math.max(position, existingPosition);
        int overlapEnd = Math.min(end, existingEnd);
        int remainingLength = length - Math.max(0, overlapEnd - overlapStart);
        if (remainingLength <= 0) {
            return TransformStep.accepted("NOOP", Map.of(), true);
        }
        int shiftBeforeStart = Math.max(0, Math.min(position, existingEnd) - existingPosition);
        int transformedPosition = Math.max(0, position - Math.min(existingLength, shiftBeforeStart));
        return TransformStep.accepted("TEXT_DELETE", Map.of("position", transformedPosition, "length", remainingLength), true);
    }

    private int intValue(Map<String, Object> operation, String key) {
        Object value = operation.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        throw new IllegalArgumentException(key + " is required for transform");
    }

    private String stringValue(Map<String, Object> operation, String key) {
        Object value = operation.get(key);
        if (value instanceof String text) {
            return text;
        }
        throw new IllegalArgumentException(key + " is required for transform");
    }

    private record TransformStep(boolean accepted, String operationType, Map<String, Object> operation, boolean transformed, String reason) {
        static TransformStep accepted(String operationType, Map<String, Object> operation, boolean transformed) {
            return new TransformStep(true, operationType, operation, transformed, null);
        }

        static TransformStep rejected(String reason) {
            return new TransformStep(false, null, Map.of(), false, reason);
        }
    }
}
