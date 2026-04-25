package com.syncforge.api.identity.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public record User(
        UUID id,
        String externalUserKey,
        String displayName,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
