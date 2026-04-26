package com.syncforge.api.presence.api;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.syncforge.api.presence.model.UserPresence;

public record UserPresenceResponse(
        UUID userId,
        String status,
        int activeConnectionCount,
        List<String> activeDeviceIds,
        OffsetDateTime lastSeenAt,
        OffsetDateTime updatedAt
) {
    public static UserPresenceResponse from(UserPresence presence) {
        return new UserPresenceResponse(
                presence.userId(),
                presence.status(),
                presence.activeConnectionCount(),
                presence.activeDeviceIds(),
                presence.lastSeenAt(),
                presence.updatedAt());
    }
}
