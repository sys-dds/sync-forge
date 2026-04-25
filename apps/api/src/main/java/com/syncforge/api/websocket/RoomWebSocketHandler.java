package com.syncforge.api.websocket;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncforge.api.connection.application.ConnectionRegistryService;
import com.syncforge.api.identity.store.UserRepository;
import com.syncforge.api.room.application.RoomPermissionService;
import com.syncforge.api.room.store.RoomRepository;
import com.syncforge.api.shared.ForbiddenException;
import com.syncforge.api.shared.NotFoundException;
import com.syncforge.api.shared.RequestValidator;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class RoomWebSocketHandler extends TextWebSocketHandler {
    private final ObjectMapper objectMapper;
    private final UserRepository userRepository;
    private final RoomRepository roomRepository;
    private final RoomPermissionService permissionService;
    private final ConnectionRegistryService connectionRegistryService;
    private final Map<String, JoinedConnection> joinedConnections = new ConcurrentHashMap<>();

    public RoomWebSocketHandler(
            ObjectMapper objectMapper,
            UserRepository userRepository,
            RoomRepository roomRepository,
            RoomPermissionService permissionService,
            ConnectionRegistryService connectionRegistryService) {
        this.objectMapper = objectMapper;
        this.userRepository = userRepository;
        this.roomRepository = roomRepository;
        this.permissionService = permissionService;
        this.connectionRegistryService = connectionRegistryService;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        RoomWebSocketEnvelope envelope;
        try {
            envelope = objectMapper.readValue(message.getPayload(), RoomWebSocketEnvelope.class);
        } catch (JsonProcessingException exception) {
            sendError(session, null, null, "INVALID_JSON", "Message must be valid JSON");
            return;
        }

        if (envelope.type() == null || envelope.type().isBlank()) {
            sendError(session, envelope.messageId(), envelope.roomId(), "UNKNOWN_MESSAGE_TYPE", "Message type is required");
            return;
        }

        switch (envelope.type()) {
            case "JOIN_ROOM" -> joinRoom(session, envelope);
            case "LEAVE_ROOM" -> leaveRoom(session, envelope);
            case "PING" -> ping(session, envelope);
            default -> sendError(session, envelope.messageId(), envelope.roomId(), "UNKNOWN_MESSAGE_TYPE", "Unknown message type");
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        JoinedConnection joined = joinedConnections.remove(session.getId());
        if (joined != null) {
            connectionRegistryService.socketClosed(joined.roomId(), joined.userId(), joined.connectionId(), status.getCode());
        }
    }

    private void joinRoom(WebSocketSession session, RoomWebSocketEnvelope envelope) throws IOException {
        UUID userId = parseHandshakeUser(session, envelope);
        if (userId == null) {
            return;
        }
        UUID roomId = parseRoomId(session, envelope);
        if (roomId == null) {
            return;
        }
        if (!userRepository.existsById(userId)) {
            sendError(session, envelope.messageId(), envelope.roomId(), "INVALID_USER", "User does not exist");
            return;
        }
        if (!roomRepository.existsById(roomId)) {
            sendError(session, envelope.messageId(), envelope.roomId(), "ROOM_NOT_FOUND", "Room not found");
            return;
        }
        try {
            permissionService.requireJoin(roomId, userId);
        } catch (ForbiddenException exception) {
            sendError(session, envelope.messageId(), envelope.roomId(), "ROOM_ACCESS_DENIED", "User is not a room member");
            return;
        } catch (NotFoundException exception) {
            sendError(session, envelope.messageId(), envelope.roomId(), "ROOM_NOT_FOUND", "Room not found");
            return;
        }

        String connectionId = UUID.randomUUID().toString();
        JoinedConnection joined = new JoinedConnection(
                roomId,
                userId,
                connectionId,
                session.getId(),
                stringAttribute(session, RoomWebSocketHandshakeInterceptor.DEVICE_ID_ATTRIBUTE),
                stringAttribute(session, RoomWebSocketHandshakeInterceptor.SESSION_ID_ATTRIBUTE));
        connectionRegistryService.join(roomId, userId, connectionId, session.getId(), joined.deviceId(), joined.clientSessionId());
        joinedConnections.put(session.getId(), joined);
        send(session, new RoomWebSocketEnvelope("JOINED_ROOM", envelope.messageId(), roomId.toString(), connectionId, Map.of()));
    }

    private void leaveRoom(WebSocketSession session, RoomWebSocketEnvelope envelope) throws IOException {
        JoinedConnection joined = joinedConnections.remove(session.getId());
        if (joined == null) {
            sendError(session, envelope.messageId(), envelope.roomId(), "CONNECTION_NOT_JOINED", "Connection has not joined a room");
            return;
        }
        connectionRegistryService.leave(joined.roomId(), joined.userId(), joined.connectionId());
        send(session, new RoomWebSocketEnvelope("LEFT_ROOM", envelope.messageId(), joined.roomId().toString(), joined.connectionId(), Map.of()));
    }

    private void ping(WebSocketSession session, RoomWebSocketEnvelope envelope) throws IOException {
        JoinedConnection joined = joinedConnections.get(session.getId());
        if (joined == null) {
            sendError(session, envelope.messageId(), envelope.roomId(), "CONNECTION_NOT_JOINED", "Connection has not joined a room");
            return;
        }
        if (envelope.roomId() != null && !envelope.roomId().isBlank()) {
            UUID roomId = parseRoomId(session, envelope);
            if (roomId == null) {
                return;
            }
            if (!joined.roomId().equals(roomId)) {
                sendError(session, envelope.messageId(), envelope.roomId(), "CONNECTION_NOT_JOINED", "Connection has not joined this room");
                return;
            }
        }
        connectionRegistryService.ping(joined.roomId(), joined.userId(), joined.connectionId());
        send(session, new RoomWebSocketEnvelope("PONG", envelope.messageId(), joined.roomId().toString(), joined.connectionId(), Map.of()));
    }

    private UUID parseHandshakeUser(WebSocketSession session, RoomWebSocketEnvelope envelope) throws IOException {
        String rawUserId = stringAttribute(session, RoomWebSocketHandshakeInterceptor.USER_ID_ATTRIBUTE);
        if (rawUserId == null || rawUserId.isBlank()) {
            sendError(session, envelope.messageId(), envelope.roomId(), "MISSING_USER", "X-User-Id header is required");
            return null;
        }
        try {
            return RequestValidator.parseUuid(rawUserId, "X-User-Id");
        } catch (RuntimeException exception) {
            sendError(session, envelope.messageId(), envelope.roomId(), "INVALID_USER", "X-User-Id must be an existing user UUID");
            return null;
        }
    }

    private UUID parseRoomId(WebSocketSession session, RoomWebSocketEnvelope envelope) throws IOException {
        if (envelope.roomId() == null || envelope.roomId().isBlank()) {
            sendError(session, envelope.messageId(), envelope.roomId(), "ROOM_NOT_FOUND", "roomId is required");
            return null;
        }
        try {
            return RequestValidator.parseUuid(envelope.roomId(), "roomId");
        } catch (RuntimeException exception) {
            sendError(session, envelope.messageId(), envelope.roomId(), "ROOM_NOT_FOUND", "roomId must be a valid room UUID");
            return null;
        }
    }

    private void sendError(WebSocketSession session, String messageId, String roomId, String code, String message) throws IOException {
        JoinedConnection joined = joinedConnections.get(session.getId());
        if (joined != null) {
            connectionRegistryService.error(joined.roomId(), joined.userId(), joined.connectionId(), code, message);
        }
        String connectionId = joined == null ? null : joined.connectionId();
        send(session, new RoomWebSocketEnvelope(
                "ERROR",
                messageId,
                roomId,
                connectionId,
                new RoomWebSocketErrorPayload(code, message)));
    }

    private void send(WebSocketSession session, RoomWebSocketEnvelope envelope) throws IOException {
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(envelope)));
    }

    private String stringAttribute(WebSocketSession session, String name) {
        Object value = session.getAttributes().get(name);
        return value == null ? null : value.toString();
    }

    private record JoinedConnection(
            UUID roomId,
            UUID userId,
            String connectionId,
            String websocketSessionId,
            String deviceId,
            String clientSessionId
    ) {
    }
}
