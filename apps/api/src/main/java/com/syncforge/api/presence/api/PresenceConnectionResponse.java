package com.syncforge.api.presence.api;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.syncforge.api.presence.model.PresenceConnection;

public record PresenceConnectionResponse(
        String connectionId,
        UUID userId,
        String deviceId,
        String clientSessionId,
        String status,
        OffsetDateTime joinedAt,
        OffsetDateTime lastSeenAt,
        OffsetDateTime expiresAt,
        OffsetDateTime leftAt,
        String leaveReason
) {
    public static PresenceConnectionResponse from(PresenceConnection connection) {
        return new PresenceConnectionResponse(
                connection.connectionId(),
                connection.userId(),
                connection.deviceId(),
                connection.clientSessionId(),
                connection.status(),
                connection.joinedAt(),
                connection.lastSeenAt(),
                connection.expiresAt(),
                connection.leftAt(),
                connection.leaveReason());
    }
}
