package com.syncforge.api.workspace.api;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.syncforge.api.workspace.model.Workspace;

public record WorkspaceResponse(
        UUID id,
        String workspaceKey,
        String name,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static WorkspaceResponse from(Workspace workspace) {
        return new WorkspaceResponse(
                workspace.id(),
                workspace.workspaceKey(),
                workspace.name(),
                workspace.status(),
                workspace.createdAt(),
                workspace.updatedAt());
    }
}
