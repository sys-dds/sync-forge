package com.syncforge.api.connection.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ConnectionSession(
        UUID id,
        UUID roomId,
        UUID userId,
        String connectionId,
        String websocketSessionId,
        String deviceId,
        String clientSessionId,
        String status,
        OffsetDateTime connectedAt,
        OffsetDateTime lastSeenAt,
        OffsetDateTime disconnectedAt,
        String disconnectReason
) {
}
