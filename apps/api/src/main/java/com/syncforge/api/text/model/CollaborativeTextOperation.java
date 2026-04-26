package com.syncforge.api.text.model;

import java.util.List;
import java.util.Map;

import com.syncforge.api.shared.BadRequestException;

public record CollaborativeTextOperation(
        TextOperationType type,
        TextAnchor anchor,
        String content,
        List<String> targetAtomIds
) {
    public CollaborativeTextOperation {
        targetAtomIds = targetAtomIds == null ? List.of() : List.copyOf(targetAtomIds);
    }

    public static CollaborativeTextOperation insertAfter(TextAnchor anchor, String content) {
        return new CollaborativeTextOperation(TextOperationType.TEXT_INSERT_AFTER, anchor, content, List.of());
    }

    public static CollaborativeTextOperation deleteAtoms(List<String> targetAtomIds) {
        return new CollaborativeTextOperation(TextOperationType.TEXT_DELETE_ATOMS, TextAnchor.start(), null, targetAtomIds);
    }

    public static CollaborativeTextOperation fromPayload(String operationType, Map<String, Object> payload) {
        Map<String, Object> operation = payload == null ? Map.of() : payload;
        return switch (operationType) {
            case "TEXT_INSERT_AFTER" -> insertAfter(parseAnchor(operation), requiredString(operation, "text"));
            case "TEXT_DELETE_ATOMS" -> deleteAtoms(requiredStringList(operation, "atomIds"));
            default -> throw invalid("Unsupported convergence text operation type");
        };
    }

    public void validate() {
        if (type == TextOperationType.TEXT_INSERT_AFTER) {
            if (anchor == null) {
                throw invalid("anchor is required");
            }
            if (content == null || content.isEmpty()) {
                throw invalid("text is required");
            }
        }
        if (type == TextOperationType.TEXT_DELETE_ATOMS && targetAtomIds.isEmpty()) {
            throw invalid("atomIds is required");
        }
        if (targetAtomIds.stream().anyMatch(atomId -> atomId == null || atomId.isBlank())) {
            throw invalid("atomIds must not contain blank values");
        }
    }

    private static TextAnchor parseAnchor(Map<String, Object> operation) {
        Object value = operation.get("anchorAtomId");
        if (value == null) {
            return TextAnchor.start();
        }
        if (value instanceof String atomId) {
            return TextAnchor.after(atomId);
        }
        throw invalid("anchorAtomId must be a string");
    }

    private static String requiredString(Map<String, Object> operation, String key) {
        Object value = operation.get(key);
        if (value instanceof String text) {
            return text;
        }
        throw invalid(key + " is required");
    }

    private static List<String> requiredStringList(Map<String, Object> operation, String key) {
        Object value = operation.get(key);
        if (value instanceof List<?> raw) {
            return raw.stream()
                    .map(item -> item instanceof String text ? text : null)
                    .toList();
        }
        throw invalid(key + " is required");
    }

    private static BadRequestException invalid(String message) {
        return new BadRequestException("INVALID_TEXT_OPERATION", message);
    }
}
