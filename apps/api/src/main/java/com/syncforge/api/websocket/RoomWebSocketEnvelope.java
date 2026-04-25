package com.syncforge.api.websocket;

public record RoomWebSocketEnvelope(
        String type,
        String messageId,
        String roomId,
        String connectionId,
        Object payload
) {
}
