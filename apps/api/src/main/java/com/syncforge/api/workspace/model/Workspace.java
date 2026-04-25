package com.syncforge.api.workspace.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Workspace(
        UUID id,
        String workspaceKey,
        String name,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
