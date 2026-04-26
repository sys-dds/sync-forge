package com.syncforge.api.awareness.model;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record AwarenessState(
        UUID id,
        UUID roomId,
        UUID userId,
        String connectionId,
        String deviceId,
        String awarenessType,
        Integer cursorPosition,
        Integer anchorPosition,
        Integer focusPosition,
        Map<String, Object> metadata,
        OffsetDateTime updatedAt,
        OffsetDateTime expiresAt,
        String status
) {
}
