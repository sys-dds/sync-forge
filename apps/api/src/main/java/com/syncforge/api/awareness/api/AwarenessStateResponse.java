package com.syncforge.api.awareness.api;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import com.syncforge.api.awareness.model.AwarenessState;

public record AwarenessStateResponse(
        UUID userId,
        String connectionId,
        String deviceId,
        String awarenessType,
        Integer cursorPosition,
        Integer anchorPosition,
        Integer focusPosition,
        Map<String, Object> metadata,
        OffsetDateTime updatedAt,
        OffsetDateTime expiresAt
) {
    public static AwarenessStateResponse from(AwarenessState state) {
        return new AwarenessStateResponse(
                state.userId(),
                state.connectionId(),
                state.deviceId(),
                state.awarenessType(),
                state.cursorPosition(),
                state.anchorPosition(),
                state.focusPosition(),
                state.metadata(),
                state.updatedAt(),
                state.expiresAt());
    }
}
