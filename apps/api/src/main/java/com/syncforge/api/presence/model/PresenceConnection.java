package com.syncforge.api.presence.model;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record PresenceConnection(
        UUID id,
        UUID roomId,
        UUID userId,
        String connectionId,
        String websocketSessionId,
        String deviceId,
        String clientSessionId,
        String status,
        OffsetDateTime joinedAt,
        OffsetDateTime lastSeenAt,
        OffsetDateTime expiresAt,
        OffsetDateTime leftAt,
        String leaveReason,
        Map<String, Object> metadata
) {
}
