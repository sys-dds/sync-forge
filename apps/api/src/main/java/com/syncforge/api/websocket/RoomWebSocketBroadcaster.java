package com.syncforge.api.websocket;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

@Component
public class RoomWebSocketBroadcaster {
    private final ObjectMapper objectMapper;
    private final Map<UUID, Set<String>> roomSessions = new ConcurrentHashMap<>();
    private final Map<String, JoinedRoomConnection> sessionConnections = new ConcurrentHashMap<>();

    public RoomWebSocketBroadcaster(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void register(JoinedRoomConnection connection) {
        sessionConnections.put(connection.websocketSessionId(), connection);
        roomSessions.computeIfAbsent(connection.roomId(), ignored -> ConcurrentHashMap.newKeySet())
                .add(connection.websocketSessionId());
    }

    public JoinedRoomConnection unregister(String websocketSessionId) {
        JoinedRoomConnection removed = sessionConnections.remove(websocketSessionId);
        if (removed != null) {
            Set<String> sessions = roomSessions.get(removed.roomId());
            if (sessions != null) {
                sessions.remove(websocketSessionId);
                if (sessions.isEmpty()) {
                    roomSessions.remove(removed.roomId());
                }
            }
        }
        return removed;
    }

    public JoinedRoomConnection findByWebSocketSessionId(String websocketSessionId) {
        return sessionConnections.get(websocketSessionId);
    }

    public void broadcast(UUID roomId, RoomWebSocketEnvelope envelope) {
        Set<String> sessionIds = roomSessions.getOrDefault(roomId, Set.of());
        for (String sessionId : sessionIds) {
            JoinedRoomConnection connection = sessionConnections.get(sessionId);
            if (connection == null) {
                continue;
            }
            WebSocketSession session = connection.webSocketSession();
            if (session == null || !session.isOpen()) {
                unregister(sessionId);
                continue;
            }
            try {
                synchronized (session) {
                    session.sendMessage(new TextMessage(objectMapper.writeValueAsString(envelope)));
                }
            } catch (IOException exception) {
                unregister(sessionId);
                try {
                    session.close();
                } catch (IOException ignored) {
                    // Closing is best-effort after a failed send.
                }
            }
        }
    }
}
