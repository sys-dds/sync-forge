package com.syncforge.api.shared;

import java.util.UUID;

public final class RequestValidator {
    private RequestValidator() {
    }

    public static String requiredText(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            throw new BadRequestException("INVALID_REQUEST", fieldName + " is required");
        }
        return value.trim();
    }

    public static UUID parseUuid(String value, String fieldName) {
        try {
            return UUID.fromString(requiredText(value, fieldName));
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException("INVALID_UUID", fieldName + " must be a valid UUID");
        }
    }
}
