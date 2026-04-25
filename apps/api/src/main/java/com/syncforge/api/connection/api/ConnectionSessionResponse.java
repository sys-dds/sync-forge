package com.syncforge.api.connection.api;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.syncforge.api.connection.model.ConnectionSession;

public record ConnectionSessionResponse(
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
    public static ConnectionSessionResponse from(ConnectionSession session) {
        return new ConnectionSessionResponse(
                session.id(),
                session.roomId(),
                session.userId(),
                session.connectionId(),
                session.websocketSessionId(),
                session.deviceId(),
                session.clientSessionId(),
                session.status(),
                session.connectedAt(),
                session.lastSeenAt(),
                session.disconnectedAt(),
                session.disconnectReason());
    }
}
