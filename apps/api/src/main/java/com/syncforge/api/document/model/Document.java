package com.syncforge.api.document.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Document(
        UUID id,
        UUID workspaceId,
        String documentKey,
        String title,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
