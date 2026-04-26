package com.syncforge.api.websocket;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncforge.api.awareness.application.AwarenessService;
import com.syncforge.api.awareness.model.AwarenessState;
import com.syncforge.api.backpressure.application.BackpressureService;
import com.syncforge.api.backpressure.application.SessionQuarantineService;
import com.syncforge.api.backpressure.model.RoomBackpressureState;
import com.syncforge.api.connection.application.ConnectionRegistryService;
import com.syncforge.api.documentstate.application.DocumentStateService;
import com.syncforge.api.documentstate.model.DocumentLiveState;
import com.syncforge.api.identity.store.UserRepository;
import com.syncforge.api.operation.application.OperationService;
import com.syncforge.api.operation.model.OperationSubmitResult;
import com.syncforge.api.operation.model.SubmitOperationCommand;
import com.syncforge.api.presence.application.PresenceService;
import com.syncforge.api.ratelimit.application.OperationRateLimitService;
import com.syncforge.api.ratelimit.model.RateLimitDecision;
import com.syncforge.api.resume.application.ClientOffsetService;
import com.syncforge.api.resume.application.ResumeTokenService;
import com.syncforge.api.resume.application.RoomBackfillService;
import com.syncforge.api.resume.model.BackfillResult;
import com.syncforge.api.resume.model.IssuedResumeToken;
import com.syncforge.api.resume.model.ResumeToken;
import com.syncforge.api.room.application.RoomPermissionService;
import com.syncforge.api.room.store.RoomRepository;
import com.syncforge.api.shared.ForbiddenException;
import com.syncforge.api.shared.NotFoundException;
import com.syncforge.api.shared.RequestValidator;
import com.syncforge.api.stream.application.RoomEventStreamProperties;
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
    private final OperationRateLimitService operationRateLimitService;
    private final BackpressureService backpressureService;
    private final SessionQuarantineService sessionQuarantineService;
    private final DocumentStateService documentStateService;
    private final ResumeTokenService resumeTokenService;
    private final ClientOffsetService clientOffsetService;
    private final RoomBackfillService roomBackfillService;
    private final RoomEventStreamProperties streamProperties;
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
            OperationRateLimitService operationRateLimitService,
            BackpressureService backpressureService,
            SessionQuarantineService sessionQuarantineService,
            DocumentStateService documentStateService,
            ResumeTokenService resumeTokenService,
            ClientOffsetService clientOffsetService,
            RoomBackfillService roomBackfillService,
            RoomEventStreamProperties streamProperties,
            RoomWebSocketBroadcaster broadcaster) {
        this.objectMapper = objectMapper;
        this.userRepository = userRepository;
        this.roomRepository = roomRepository;
        this.permissionService = permissionService;
        this.connectionRegistryService = connectionRegistryService;
        this.presenceService = presenceService;
        this.awarenessService = awarenessService;
        this.operationService = operationService;
        this.operationRateLimitService = operationRateLimitService;
        this.backpressureService = backpressureService;
        this.sessionQuarantineService = sessionQuarantineService;
        this.documentStateService = documentStateService;
        this.resumeTokenService = resumeTokenService;
        this.clientOffsetService = clientOffsetService;
        this.roomBackfillService = roomBackfillService;
        this.streamProperties = streamProperties;
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
            case "GET_DOCUMENT_STATE" -> getDocumentState(session, envelope);
            case "ACK_ROOM_EVENT" -> ackRoomEvent(session, envelope);
            case "RESUME_ROOM" -> resumeRoom(session, envelope);
            default -> sendError(session, envelope.messageId(), envelope.roomId(), "UNKNOWN_MESSAGE_TYPE", "Unknown message type");
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        JoinedRoomConnection joined = broadcaster.unregister(session.getId());
        if (joined != null) {
            presenceService.leave(joined.roomId(), joined.userId(), joined.connectionId(), "SOCKET_CLOSED");
            connectionRegistryService.socketClosed(joined.roomId(), joined.userId(), joined.connectionId(), status.getCode());
            sessionQuarantineService.releaseDisconnected(joined.connectionId());
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
        DocumentLiveState state = documentStateService.getOrInitialize(roomId);
        IssuedResumeToken resumeToken = resumeTokenService.issue(roomId, userId, connectionId, joined.clientSessionId(), state.currentRoomSeq());
        send(session, new RoomWebSocketEnvelope("JOINED_ROOM", envelope.messageId(), roomId.toString(), connectionId,
                joinedPayload(resumeToken.token(), state)));
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
        String operationId = stringPayload(payload, "operationId");
        if (sessionQuarantineService.isQuarantined(joined.connectionId())) {
            send(session, new RoomWebSocketEnvelope(
                    "SESSION_QUARANTINED",
                    envelope.messageId(),
                    envelope.roomId(),
                    joined.connectionId(),
                    Map.of(
                            "connectionId", joined.connectionId(),
                            "reason", "SLOW_CONSUMER",
                            "expiresAt", sessionQuarantineService.active(joined.connectionId())
                                    .map(quarantine -> quarantine.expiresAt().toString())
                                    .orElse(""))));
            return;
        }
        if (backpressureService.shouldRejectNewOperation(joined.roomId())) {
            sendOperationNack(session, envelope, joined.connectionId(), OperationSubmitResult.nack(
                    operationId,
                    longPayload(payload, "clientSeq"),
                    "ROOM_BACKPRESSURE",
                    "room is rejecting new operations due to pending event pressure",
                    null));
            return;
        }
        RateLimitDecision rateLimitDecision = operationRateLimitService.check(
                joined.roomId(),
                joined.userId(),
                joined.connectionId(),
                joined.clientSessionId(),
                operationId);
        if (!rateLimitDecision.allowed()) {
            sendRateLimited(session, envelope, joined.connectionId(), rateLimitDecision);
            return;
        }
        OperationSubmitResult result = operationService.submit(new SubmitOperationCommand(
                joined.roomId(),
                joined.userId(),
                joined.connectionId(),
                joined.clientSessionId(),
                operationId,
                longPayload(payload, "clientSeq"),
                longPayload(payload, "baseRevision"),
                stringPayload(payload, "operationType"),
                mapPayload(payload, "operation")));
        if (result.accepted()) {
            sendOperationAck(session, envelope, joined.connectionId(), result);
            if (!result.duplicate()) {
                if (!streamProperties.enabled()) {
                    broadcaster.broadcast(joined.roomId(), new RoomWebSocketEnvelope(
                            "OPERATION_APPLIED",
                            envelope.messageId(),
                            joined.roomId().toString(),
                            joined.connectionId(),
                            operationAppliedPayload(joined, result)));
                }
                RoomBackpressureState backpressure = backpressureService.recordAcceptedRoomEvent(joined.roomId());
                if (backpressure.warning()) {
                    broadcaster.broadcast(joined.roomId(), new RoomWebSocketEnvelope(
                            "BACKPRESSURE_WARNING",
                            envelope.messageId(),
                            joined.roomId().toString(),
                            joined.connectionId(),
                            backpressureWarningPayload(joined.roomId(), backpressure)));
                }
            }
            return;
        }
        sendOperationNack(session, envelope, joined.connectionId(), result);
    }

    private void getDocumentState(WebSocketSession session, RoomWebSocketEnvelope envelope) throws IOException {
        JoinedRoomConnection joined = requireJoinedForRoom(session, envelope);
        if (joined == null) {
            return;
        }
        DocumentLiveState state = documentStateService.getOrInitialize(joined.roomId());
        send(session, new RoomWebSocketEnvelope("DOCUMENT_STATE", envelope.messageId(), joined.roomId().toString(),
                joined.connectionId(), documentStatePayload(state)));
    }

    private void ackRoomEvent(WebSocketSession session, RoomWebSocketEnvelope envelope) throws IOException {
        JoinedRoomConnection joined = requireJoinedForRoom(session, envelope);
        if (joined == null) {
            return;
        }
        Map<String, Object> payload = payloadMap(envelope);
        Long roomSeq = payload == null ? null : longPayload(payload, "roomSeq");
        if (roomSeq == null || roomSeq < 0) {
            sendError(session, envelope.messageId(), envelope.roomId(), "INVALID_PAYLOAD", "roomSeq is required and must be non-negative");
            return;
        }
        clientOffsetService.acknowledge(joined.roomId(), joined.userId(), joined.clientSessionId(), roomSeq);
        backpressureService.acknowledgeRoomEvent(joined.roomId());
        String token = payload == null ? null : stringPayload(payload, "resumeToken");
        if (token != null && !token.isBlank()) {
            resumeTokenService.updateLastSeen(token, roomSeq);
        }
        send(session, new RoomWebSocketEnvelope("ROOM_EVENT_ACKED", envelope.messageId(), joined.roomId().toString(),
                joined.connectionId(), Map.of("roomSeq", roomSeq)));
    }

    private void resumeRoom(WebSocketSession session, RoomWebSocketEnvelope envelope) throws IOException {
        JoinedRoomConnection activeConnection = broadcaster.findByWebSocketSessionId(session.getId());
        if (activeConnection != null) {
            if (sessionQuarantineService.isQuarantined(activeConnection.connectionId())) {
                send(session, new RoomWebSocketEnvelope(
                        "SESSION_QUARANTINED",
                        envelope.messageId(),
                        envelope.roomId(),
                        activeConnection.connectionId(),
                        Map.of(
                                "connectionId", activeConnection.connectionId(),
                                "reason", "SLOW_CONSUMER",
                                "expiresAt", sessionQuarantineService.active(activeConnection.connectionId())
                                        .map(quarantine -> quarantine.expiresAt().toString())
                                        .orElse(""))));
                return;
            }
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
        Map<String, Object> payload = payloadMap(envelope);
        String token = payload == null ? null : stringPayload(payload, "resumeToken");
        Long requestedLastSeen = payload == null ? null : longPayload(payload, "lastSeenRoomSeq");
        String clientSessionId = stringAttribute(session, RoomWebSocketHandshakeInterceptor.SESSION_ID_ATTRIBUTE);
        ResumeToken validated;
        try {
            validated = resumeTokenService.validate(token, roomId, userId, clientSessionId);
        } catch (ForbiddenException exception) {
            sendError(session, envelope.messageId(), envelope.roomId(), exception.code(), exception.getMessage());
            return;
        }

        String connectionId = UUID.randomUUID().toString();
        JoinedRoomConnection joined = new JoinedRoomConnection(
                roomId,
                userId,
                connectionId,
                session.getId(),
                stringAttribute(session, RoomWebSocketHandshakeInterceptor.DEVICE_ID_ATTRIBUTE),
                clientSessionId,
                session);
        connectionRegistryService.join(roomId, userId, connectionId, session.getId(), joined.deviceId(), joined.clientSessionId());
        presenceService.join(roomId, userId, connectionId, session.getId(), joined.deviceId(), joined.clientSessionId());
        broadcaster.register(joined);

        DocumentLiveState state = documentStateService.getOrInitialize(roomId);
        IssuedResumeToken replacement = resumeTokenService.issue(roomId, userId, connectionId, joined.clientSessionId(), validated.lastSeenRoomSeq());
        send(session, new RoomWebSocketEnvelope("ROOM_RESUMED", envelope.messageId(), roomId.toString(), connectionId,
                joinedPayload(replacement.token(), state)));

        long lastSeenRoomSeq = requestedLastSeen == null
                ? clientOffsetService.lastSeenOrDefault(roomId, userId, clientSessionId, validated.lastSeenRoomSeq())
                : requestedLastSeen;
        BackfillResult backfill = roomBackfillService.backfill(roomId, userId, clientSessionId, lastSeenRoomSeq);
        if (backfill.resyncRequired()) {
            send(session, new RoomWebSocketEnvelope("RESYNC_REQUIRED", envelope.messageId(), roomId.toString(), connectionId,
                    Map.of("reason", backfill.reason(), "documentState", documentStatePayload(backfill.currentState()))));
            return;
        }
        send(session, new RoomWebSocketEnvelope("ROOM_BACKFILL", envelope.messageId(), roomId.toString(), connectionId,
                Map.of("fromRoomSeq", backfill.fromRoomSeq(), "toRoomSeq", backfill.toRoomSeq(), "events", backfill.events())));
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
        payload.put("transformed", result.transformed());
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

    private void sendRateLimited(WebSocketSession session, RoomWebSocketEnvelope envelope, String connectionId, RateLimitDecision decision) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", "OPERATION_RATE_LIMITED");
        payload.put("limit", decision.limitValue());
        payload.put("windowSeconds", decision.windowSeconds());
        payload.put("retryAfterMs", decision.retryAfterMs());
        payload.put("limitKey", decision.limitKey());
        send(session, new RoomWebSocketEnvelope("RATE_LIMITED", envelope.messageId(), envelope.roomId(), connectionId, payload));
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
        payload.put("transformed", result.transformed());
        return payload;
    }

    private Map<String, Object> backpressureWarningPayload(UUID roomId, RoomBackpressureState state) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("roomId", roomId.toString());
        payload.put("policy", "DELAY_OR_REJECT_NEW_OPERATIONS");
        payload.put("pendingEvents", state.pendingEvents());
        payload.put("maxPendingEvents", state.maxPendingEvents());
        payload.put("status", state.status());
        return payload;
    }

    private Map<String, Object> joinedPayload(String resumeToken, DocumentLiveState state) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("resumeToken", resumeToken);
        payload.put("currentRoomSeq", state.currentRoomSeq());
        payload.put("currentRevision", state.currentRevision());
        return payload;
    }

    private Map<String, Object> documentStatePayload(DocumentLiveState state) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("roomId", state.roomId().toString());
        payload.put("documentId", state.documentId().toString());
        payload.put("currentRoomSeq", state.currentRoomSeq());
        payload.put("currentRevision", state.currentRevision());
        payload.put("contentText", state.contentText());
        payload.put("contentChecksum", state.contentChecksum());
        payload.put("updatedAt", state.updatedAt());
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
