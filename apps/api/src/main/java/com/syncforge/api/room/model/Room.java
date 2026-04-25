package com.syncforge.api.room.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Room(
        UUID id,
        UUID workspaceId,
        UUID documentId,
        String roomKey,
        String roomType,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
