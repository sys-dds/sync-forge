package com.syncforge.api.node;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CrossNodePresenceState(
        UUID roomId,
        UUID userId,
        String nodeId,
        int activeConnectionCount,
        OffsetDateTime lastSeenAt,
        String status) {
}
