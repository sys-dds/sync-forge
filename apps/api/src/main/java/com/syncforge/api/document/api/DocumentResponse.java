package com.syncforge.api.document.api;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.syncforge.api.document.model.Document;

public record DocumentResponse(
        UUID id,
        UUID workspaceId,
        String documentKey,
        String title,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static DocumentResponse from(Document document) {
        return new DocumentResponse(
                document.id(),
                document.workspaceId(),
                document.documentKey(),
                document.title(),
                document.status(),
                document.createdAt(),
                document.updatedAt());
    }
}
