package com.syncforge.api.presence.model;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record UserPresence(
        UUID roomId,
        UUID userId,
        String status,
        int activeConnectionCount,
        List<String> activeDeviceIds,
        OffsetDateTime lastSeenAt,
        OffsetDateTime updatedAt
) {
}
