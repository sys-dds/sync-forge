package com.syncforge.api.runtime;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record RoomRuntimeControlEvent(
        UUID id,
        UUID roomId,
        UUID actorUserId,
        String action,
        String reason,
        Map<String, Object> previousState,
        Map<String, Object> newState,
        OffsetDateTime createdAt
) {
}
