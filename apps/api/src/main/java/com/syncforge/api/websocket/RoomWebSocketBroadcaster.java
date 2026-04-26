package com.syncforge.api.websocket;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.syncforge.api.backpressure.application.BoundedWebSocketSender;
import com.syncforge.api.stream.application.NodeRoomSubscriptionService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

@Component
public class RoomWebSocketBroadcaster {
    private final BoundedWebSocketSender boundedSender;
    private final NodeRoomSubscriptionService subscriptionService;
    private final Map<UUID, Set<String>> roomSessions = new ConcurrentHashMap<>();
    private final Map<String, JoinedRoomConnection> sessionConnections = new ConcurrentHashMap<>();
    private final Map<String, DeliveredEventWindow> deliveredOperationEvents = new ConcurrentHashMap<>();

    public RoomWebSocketBroadcaster(BoundedWebSocketSender boundedSender, NodeRoomSubscriptionService subscriptionService) {
        this.boundedSender = boundedSender;
        this.subscriptionService = subscriptionService;
    }

    public void register(JoinedRoomConnection connection) {
        sessionConnections.put(connection.websocketSessionId(), connection);
        deliveredOperationEvents.put(connection.websocketSessionId(), new DeliveredEventWindow());
        roomSessions.computeIfAbsent(connection.roomId(), ignored -> ConcurrentHashMap.newKeySet())
                .add(connection.websocketSessionId());
        boundedSender.register(connection);
        subscriptionService.joined(connection.roomId());
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
            deliveredOperationEvents.remove(websocketSessionId);
            boundedSender.unregister(removed);
            subscriptionService.left(removed.roomId());
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
            if (alreadyDeliveredOperationEvent(sessionId, envelope)) {
                continue;
            }
            if (!boundedSender.send(connection, envelope)) {
                unregister(sessionId);
                try {
                    session.close();
                } catch (Exception ignored) {
                    // Closing is best-effort after a failed send.
                }
            }
        }
    }

    private boolean alreadyDeliveredOperationEvent(String sessionId, RoomWebSocketEnvelope envelope) {
        if (!"OPERATION_APPLIED".equals(envelope.type()) || !(envelope.payload() instanceof Map<?, ?> payload)) {
            return false;
        }
        Object rawEventId = payload.get("eventId");
        if (rawEventId == null || rawEventId.toString().isBlank()) {
            return false;
        }
        return !deliveredOperationEvents.computeIfAbsent(sessionId, ignored -> new DeliveredEventWindow())
                .add(rawEventId.toString());
    }

    private static final class DeliveredEventWindow {
        private static final int MAX_RECENT_EVENTS_PER_SESSION = 1024;
        private final LinkedHashMap<String, Boolean> eventIds = new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > MAX_RECENT_EVENTS_PER_SESSION;
            }
        };

        synchronized boolean add(String eventId) {
            if (eventIds.containsKey(eventId)) {
                return false;
            }
            eventIds.put(eventId, Boolean.TRUE);
            return true;
        }
    }
}
