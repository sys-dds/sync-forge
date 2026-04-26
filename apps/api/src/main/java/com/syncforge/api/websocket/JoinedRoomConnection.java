package com.syncforge.api.websocket;

import java.util.UUID;

import org.springframework.web.socket.WebSocketSession;

public record JoinedRoomConnection(
        UUID roomId,
        UUID userId,
        String connectionId,
        String websocketSessionId,
        String deviceId,
        String clientSessionId,
        WebSocketSession webSocketSession
) {
}
