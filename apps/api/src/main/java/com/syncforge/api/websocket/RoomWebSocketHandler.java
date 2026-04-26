package com.syncforge.api.websocket;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncforge.api.awareness.application.AwarenessService;
import com.syncforge.api.awareness.model.AwarenessState;
import com.syncforge.api.connection.application.ConnectionRegistryService;
import com.syncforge.api.identity.store.UserRepository;
import com.syncforge.api.operation.application.OperationService;
import com.syncforge.api.operation.model.OperationSubmitResult;
import com.syncforge.api.operation.model.SubmitOperationCommand;
import com.syncforge.api.presence.application.PresenceService;
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
    private final PresenceService presenceService;
    private final AwarenessService awarenessService;
    private final OperationService operationService;
    private final RoomWebSocketBroadcaster broadcaster;

    public RoomWebSocketHandler(
            ObjectMapper objectMapper,
            UserRepository userRepository,
            RoomRepository roomRepository,
            RoomPermissionService permissionService,
            ConnectionRegistryService connectionRegistryService,
            PresenceService presenceService,
            AwarenessService awarenessService,
            OperationService operationService,
            RoomWebSocketBroadcaster broadcaster) {
        this.objectMapper = objectMapper;
        this.userRepository = userRepository;
        this.roomRepository = roomRepository;
        this.permissionService = permissionService;
        this.connectionRegistryService = connectionRegistryService;
        this.presenceService = presenceService;
        this.awarenessService = awarenessService;
        this.operationService = operationService;
        this.broadcaster = broadcaster;
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
            case "HEARTBEAT" -> heartbeat(session, envelope);
            case "CURSOR_UPDATE" -> cursorUpdate(session, envelope);
            case "SELECTION_UPDATE" -> selectionUpdate(session, envelope);
            case "SUBMIT_OPERATION" -> submitOperation(session, envelope);
            default -> sendError(session, envelope.messageId(), envelope.roomId(), "UNKNOWN_MESSAGE_TYPE", "Unknown message type");
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        JoinedRoomConnection joined = broadcaster.unregister(session.getId());
        if (joined != null) {
            presenceService.leave(joined.roomId(), joined.userId(), joined.connectionId(), "SOCKET_CLOSED");
            connectionRegistryService.socketClosed(joined.roomId(), joined.userId(), joined.connectionId(), status.getCode());
            broadcaster.broadcast(joined.roomId(), new RoomWebSocketEnvelope(
                    "PRESENCE_LEFT",
                    null,
                    joined.roomId().toString(),
                    joined.connectionId(),
                    Map.of("userId", joined.userId().toString(), "connectionId", joined.connectionId(), "reason", "SOCKET_CLOSED")));
        }
    }

    private void joinRoom(WebSocketSession session, RoomWebSocketEnvelope envelope) throws IOException {
        if (broadcaster.findByWebSocketSessionId(session.getId()) != null) {
            sendError(session, envelope.messageId(), envelope.roomId(), "ALREADY_JOINED_ROOM", "Connection has already joined a room");
            return;
        }
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
        JoinedRoomConnection joined = new JoinedRoomConnection(
                roomId,
                userId,
                connectionId,
                session.getId(),
                stringAttribute(session, RoomWebSocketHandshakeInterceptor.DEVICE_ID_ATTRIBUTE),
                stringAttribute(session, RoomWebSocketHandshakeInterceptor.SESSION_ID_ATTRIBUTE),
                session);
        connectionRegistryService.join(roomId, userId, connectionId, session.getId(), joined.deviceId(), joined.clientSessionId());
        presenceService.join(roomId, userId, connectionId, session.getId(), joined.deviceId(), joined.clientSessionId());
        broadcaster.register(joined);
        send(session, new RoomWebSocketEnvelope("JOINED_ROOM", envelope.messageId(), roomId.toString(), connectionId, Map.of()));
        send(session, new RoomWebSocketEnvelope(
                "PRESENCE_SNAPSHOT",
                envelope.messageId(),
                roomId.toString(),
                connectionId,
                presenceSnapshotPayload(roomId)));
        broadcaster.broadcast(roomId, new RoomWebSocketEnvelope(
                "PRESENCE_JOINED",
                envelope.messageId(),
                roomId.toString(),
                connectionId,
                Map.of("userId", userId.toString(), "connectionId", connectionId, "deviceId", joined.deviceId() == null ? "" : joined.deviceId())));
    }

    private void leaveRoom(WebSocketSession session, RoomWebSocketEnvelope envelope) throws IOException {
        JoinedRoomConnection joined = broadcaster.findByWebSocketSessionId(session.getId());
        if (joined == null) {
            sendError(session, envelope.messageId(), envelope.roomId(), "CONNECTION_NOT_JOINED", "Connection has not joined a room");
            return;
        }
        presenceService.leave(joined.roomId(), joined.userId(), joined.connectionId(), "CLIENT_LEFT");
        connectionRegistryService.leave(joined.roomId(), joined.userId(), joined.connectionId());
        send(session, new RoomWebSocketEnvelope("LEFT_ROOM", envelope.messageId(), joined.roomId().toString(), joined.connectionId(), Map.of()));
        broadcaster.unregister(session.getId());
        broadcaster.broadcast(joined.roomId(), new RoomWebSocketEnvelope(
                "PRESENCE_LEFT",
                envelope.messageId(),
                joined.roomId().toString(),
                joined.connectionId(),
                Map.of("userId", joined.userId().toString(), "connectionId", joined.connectionId(), "reason", "CLIENT_LEFT")));
    }

    private void ping(WebSocketSession session, RoomWebSocketEnvelope envelope) throws IOException {
        JoinedRoomConnection joined = broadcaster.findByWebSocketSessionId(session.getId());
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
        presenceService.heartbeat(joined.roomId(), joined.userId(), joined.connectionId());
        send(session, new RoomWebSocketEnvelope("PONG", envelope.messageId(), joined.roomId().toString(), joined.connectionId(), Map.of()));
        broadcaster.broadcast(joined.roomId(), new RoomWebSocketEnvelope(
                "PRESENCE_UPDATED",
                envelope.messageId(),
                joined.roomId().toString(),
                joined.connectionId(),
                Map.of("userId", joined.userId().toString(), "connectionId", joined.connectionId())));
    }

    private void heartbeat(WebSocketSession session, RoomWebSocketEnvelope envelope) throws IOException {
        JoinedRoomConnection joined = broadcaster.findByWebSocketSessionId(session.getId());
        if (joined == null) {
            sendError(session, envelope.messageId(), envelope.roomId(), "CONNECTION_NOT_JOINED", "Connection has not joined a room");
            return;
        }
        presenceService.heartbeat(joined.roomId(), joined.userId(), joined.connectionId());
        send(session, new RoomWebSocketEnvelope("PONG", envelope.messageId(), joined.roomId().toString(), joined.connectionId(), Map.of()));
        broadcaster.broadcast(joined.roomId(), new RoomWebSocketEnvelope(
                "PRESENCE_UPDATED",
                envelope.messageId(),
                joined.roomId().toString(),
                joined.connectionId(),
                Map.of("userId", joined.userId().toString(), "connectionId", joined.connectionId())));
    }

    private void cursorUpdate(WebSocketSession session, RoomWebSocketEnvelope envelope) throws IOException {
        JoinedRoomConnection joined = requireJoinedForRoom(session, envelope);
        if (joined == null) {
            return;
        }
        Map<String, Object> payload = payloadMap(envelope);
        if (payload == null) {
            sendError(session, envelope.messageId(), envelope.roomId(), "INVALID_PAYLOAD", "payload must be an object");
            return;
        }
        try {
            AwarenessState state = awarenessService.updateCursor(
                    joined.roomId(),
                    joined.userId(),
                    joined.connectionId(),
                    integerPayload(payload, "cursorPosition"),
                    metadataPayload(payload));
            broadcaster.broadcast(joined.roomId(), new RoomWebSocketEnvelope(
                    "AWARENESS_UPDATED",
                    envelope.messageId(),
                    joined.roomId().toString(),
                    joined.connectionId(),
                    awarenessPayload(state)));
        } catch (RuntimeException exception) {
            sendError(session, envelope.messageId(), envelope.roomId(), "INVALID_PAYLOAD", exception.getMessage());
        }
    }

    private void selectionUpdate(WebSocketSession session, RoomWebSocketEnvelope envelope) throws IOException {
        JoinedRoomConnection joined = requireJoinedForRoom(session, envelope);
        if (joined == null) {
            return;
        }
        Map<String, Object> payload = payloadMap(envelope);
        if (payload == null) {
            sendError(session, envelope.messageId(), envelope.roomId(), "INVALID_PAYLOAD", "payload must be an object");
            return;
        }
        try {
            AwarenessState state = awarenessService.updateSelection(
                    joined.roomId(),
                    joined.userId(),
                    joined.connectionId(),
                    integerPayload(payload, "anchorPosition"),
                    integerPayload(payload, "focusPosition"),
                    metadataPayload(payload));
            broadcaster.broadcast(joined.roomId(), new RoomWebSocketEnvelope(
                    "AWARENESS_UPDATED",
                    envelope.messageId(),
                    joined.roomId().toString(),
                    joined.connectionId(),
                    awarenessPayload(state)));
        } catch (RuntimeException exception) {
            sendError(session, envelope.messageId(), envelope.roomId(), "INVALID_PAYLOAD", exception.getMessage());
        }
    }

    private JoinedRoomConnection requireJoinedForRoom(WebSocketSession session, RoomWebSocketEnvelope envelope) throws IOException {
        JoinedRoomConnection joined = broadcaster.findByWebSocketSessionId(session.getId());
        if (joined == null) {
            sendError(session, envelope.messageId(), envelope.roomId(), "CONNECTION_NOT_JOINED", "Connection has not joined a room");
            return null;
        }
        if (envelope.roomId() == null || envelope.roomId().isBlank()) {
            return joined;
        }
        UUID roomId = parseRoomId(session, envelope);
        if (roomId == null) {
            return null;
        }
        if (!joined.roomId().equals(roomId)) {
            sendError(session, envelope.messageId(), envelope.roomId(), "CONNECTION_NOT_JOINED", "Connection has not joined this room");
            return null;
        }
        return joined;
    }

    private void submitOperation(WebSocketSession session, RoomWebSocketEnvelope envelope) throws IOException {
        JoinedRoomConnection joined = requireJoinedForRoom(session, envelope);
        if (joined == null) {
            return;
        }
        Map<String, Object> payload = payloadMap(envelope);
        if (payload == null) {
            sendOperationNack(session, envelope, joined.connectionId(), OperationSubmitResult.nack(null, null, "INVALID_OPERATION",
                    "payload must be an object", null));
            return;
        }
        OperationSubmitResult result = operationService.submit(new SubmitOperationCommand(
                joined.roomId(),
                joined.userId(),
                joined.connectionId(),
                joined.clientSessionId(),
                stringPayload(payload, "operationId"),
                longPayload(payload, "clientSeq"),
                longPayload(payload, "baseRevision"),
                stringPayload(payload, "operationType"),
                mapPayload(payload, "operation")));
        if (result.accepted()) {
            sendOperationAck(session, envelope, joined.connectionId(), result);
            if (!result.duplicate()) {
                broadcaster.broadcast(joined.roomId(), new RoomWebSocketEnvelope(
                        "OPERATION_APPLIED",
                        envelope.messageId(),
                        joined.roomId().toString(),
                        joined.connectionId(),
                        operationAppliedPayload(joined, result)));
            }
            return;
        }
        sendOperationNack(session, envelope, joined.connectionId(), result);
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
        JoinedRoomConnection joined = broadcaster.findByWebSocketSessionId(session.getId());
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
        synchronized (session) {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(envelope)));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payloadMap(RoomWebSocketEnvelope envelope) {
        return envelope.payload() instanceof Map<?, ?> payload ? (Map<String, Object>) payload : null;
    }

    private Integer integerPayload(Map<String, Object> payload, String field) {
        Object value = payload.get(field);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return null;
    }

    private Long longPayload(Map<String, Object> payload, String field) {
        Object value = payload.get(field);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return null;
    }

    private String stringPayload(Map<String, Object> payload, String field) {
        Object value = payload.get(field);
        return value == null ? null : value.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapPayload(Map<String, Object> payload, String field) {
        Object value = payload.get(field);
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> metadataPayload(Map<String, Object> payload) {
        Object value = payload.get("metadata");
        return value instanceof Map<?, ?> metadata ? (Map<String, Object>) metadata : Map.of();
    }

    private Map<String, Object> awarenessPayload(AwarenessState state) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", state.userId().toString());
        payload.put("connectionId", state.connectionId());
        payload.put("awarenessType", state.awarenessType());
        payload.put("cursorPosition", state.cursorPosition());
        payload.put("anchorPosition", state.anchorPosition());
        payload.put("focusPosition", state.focusPosition());
        payload.put("metadata", state.metadata());
        return payload;
    }

    private void sendOperationAck(WebSocketSession session, RoomWebSocketEnvelope envelope, String connectionId, OperationSubmitResult result) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("operationId", result.operationId());
        payload.put("clientSeq", result.clientSeq());
        payload.put("roomSeq", result.roomSeq());
        payload.put("revision", result.revision());
        payload.put("duplicate", result.duplicate());
        send(session, new RoomWebSocketEnvelope("OPERATION_ACK", envelope.messageId(), envelope.roomId(), connectionId, payload));
    }

    private void sendOperationNack(WebSocketSession session, RoomWebSocketEnvelope envelope, String connectionId, OperationSubmitResult result) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("operationId", result.operationId());
        payload.put("clientSeq", result.clientSeq());
        payload.put("code", result.code());
        payload.put("message", result.message());
        payload.put("currentRevision", result.currentRevision());
        send(session, new RoomWebSocketEnvelope("OPERATION_NACK", envelope.messageId(), envelope.roomId(), connectionId, payload));
    }

    private Map<String, Object> operationAppliedPayload(JoinedRoomConnection joined, OperationSubmitResult result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("operationId", result.operationId());
        payload.put("userId", joined.userId().toString());
        payload.put("clientSeq", result.clientSeq());
        payload.put("roomSeq", result.roomSeq());
        payload.put("revision", result.revision());
        payload.put("operationType", result.operationType());
        payload.put("operation", result.operation());
        return payload;
    }

    private Map<String, Object> presenceSnapshotPayload(UUID roomId) {
        return Map.of("users", presenceService.findRoomPresence(roomId, null).stream()
                .map(presence -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("userId", presence.userId().toString());
                    item.put("status", presence.status());
                    item.put("activeConnectionCount", presence.activeConnectionCount());
                    item.put("activeDeviceIds", presence.activeDeviceIds());
                    item.put("lastSeenAt", presence.lastSeenAt());
                    item.put("updatedAt", presence.updatedAt());
                    return item;
                })
                .toList());
    }

    private String stringAttribute(WebSocketSession session, String name) {
        Object value = session.getAttributes().get(name);
        return value == null ? null : value.toString();
    }

}
