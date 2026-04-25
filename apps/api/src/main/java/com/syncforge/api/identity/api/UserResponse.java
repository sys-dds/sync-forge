package com.syncforge.api.identity.api;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.syncforge.api.identity.model.User;

public record UserResponse(
        UUID id,
        String externalUserKey,
        String displayName,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static UserResponse from(User user) {
        return new UserResponse(
                user.id(),
                user.externalUserKey(),
                user.displayName(),
                user.status(),
                user.createdAt(),
                user.updatedAt());
    }
}
