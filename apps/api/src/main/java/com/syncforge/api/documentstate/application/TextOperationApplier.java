package com.syncforge.api.documentstate.application;

import java.util.LinkedHashMap;
import java.util.Map;

import com.syncforge.api.documentstate.model.AppliedTextOperation;
import com.syncforge.api.shared.BadRequestException;
import org.springframework.stereotype.Component;

@Component
public class TextOperationApplier {
    public AppliedTextOperation apply(String content, String operationType, Map<String, Object> operation) {
        String current = content == null ? "" : content;
        Map<String, Object> payload = operation == null ? Map.of() : new LinkedHashMap<>(operation);
        return switch (operationType) {
            case "TEXT_INSERT" -> applyInsert(current, payload);
            case "TEXT_DELETE" -> applyDelete(current, payload);
            case "TEXT_REPLACE" -> applyReplace(current, payload);
            case "NOOP" -> new AppliedTextOperation("NOOP", Map.of(), current);
            default -> throw invalid("Unsupported operation type");
        };
    }

    private AppliedTextOperation applyInsert(String content, Map<String, Object> operation) {
        int position = requiredInt(operation, "position");
        String text = requiredString(operation, "text");
        requirePosition(position, content.length(), true);
        String updated = content.substring(0, position) + text + content.substring(position);
        return new AppliedTextOperation("TEXT_INSERT", Map.of("position", position, "text", text), updated);
    }

    private AppliedTextOperation applyDelete(String content, Map<String, Object> operation) {
        int position = requiredInt(operation, "position");
        int length = requiredPositiveInt(operation, "length");
        requireRange(position, length, content.length());
        String updated = content.substring(0, position) + content.substring(position + length);
        return new AppliedTextOperation("TEXT_DELETE", Map.of("position", position, "length", length), updated);
    }

    private AppliedTextOperation applyReplace(String content, Map<String, Object> operation) {
        int position = requiredInt(operation, "position");
        int length = requiredPositiveInt(operation, "length");
        String text = requiredString(operation, "text");
        requireRange(position, length, content.length());
        String updated = content.substring(0, position) + text + content.substring(position + length);
        return new AppliedTextOperation("TEXT_REPLACE", Map.of("position", position, "length", length, "text", text), updated);
    }

    private int requiredInt(Map<String, Object> operation, String key) {
        Object value = operation.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        throw invalid(key + " is required");
    }

    private int requiredPositiveInt(Map<String, Object> operation, String key) {
        int value = requiredInt(operation, key);
        if (value <= 0) {
            throw invalid(key + " must be positive");
        }
        return value;
    }

    private String requiredString(Map<String, Object> operation, String key) {
        Object value = operation.get(key);
        if (value instanceof String text) {
            return text;
        }
        throw invalid(key + " is required");
    }

    private void requirePosition(int position, int contentLength, boolean allowEnd) {
        int max = allowEnd ? contentLength : contentLength - 1;
        if (position < 0 || position > max) {
            throw invalid("position is outside document bounds");
        }
    }

    private void requireRange(int position, int length, int contentLength) {
        if (position < 0 || position + length > contentLength) {
            throw invalid("operation range is outside document bounds");
        }
    }

    private BadRequestException invalid(String message) {
        return new BadRequestException("INVALID_OPERATION_PAYLOAD", message);
    }
}
