package com.syncforge.api.connection.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.syncforge.api.connection.model.ConnectionSession;
import com.syncforge.api.connection.store.ConnectionEventRepository;
import com.syncforge.api.connection.store.ConnectionSessionRepository;
import com.syncforge.api.identity.store.UserRepository;
import com.syncforge.api.room.store.RoomRepository;
import com.syncforge.api.shared.NotFoundException;
import org.springframework.stereotype.Service;

@Service
public class ConnectionRegistryService {
    private final ConnectionSessionRepository sessionRepository;
    private final ConnectionEventRepository eventRepository;
    private final RoomRepository roomRepository;
    private final UserRepository userRepository;

    public ConnectionRegistryService(
            ConnectionSessionRepository sessionRepository,
            ConnectionEventRepository eventRepository,
            RoomRepository roomRepository,
            UserRepository userRepository) {
        this.sessionRepository = sessionRepository;
        this.eventRepository = eventRepository;
        this.roomRepository = roomRepository;
        this.userRepository = userRepository;
    }

    public void join(
            UUID roomId,
            UUID userId,
            String connectionId,
            String websocketSessionId,
            String deviceId,
            String clientSessionId) {
        sessionRepository.createConnected(roomId, userId, connectionId, websocketSessionId, deviceId, clientSessionId);
        eventRepository.record(roomId, userId, connectionId, "CONNECTED", Map.of());
        eventRepository.record(roomId, userId, connectionId, "JOINED_ROOM", Map.of());
    }

    public void ping(UUID roomId, UUID userId, String connectionId) {
        sessionRepository.touch(connectionId);
        eventRepository.record(roomId, userId, connectionId, "PING", Map.of());
    }

    public void leave(UUID roomId, UUID userId, String connectionId) {
        sessionRepository.disconnect(connectionId, "CLIENT_LEFT");
        eventRepository.record(roomId, userId, connectionId, "LEFT_ROOM", Map.of("reason", "CLIENT_LEFT"));
    }

    public void socketClosed(UUID roomId, UUID userId, String connectionId, int statusCode) {
        sessionRepository.disconnect(connectionId, "SOCKET_CLOSED");
        eventRepository.record(roomId, userId, connectionId, "DISCONNECTED",
                Map.of("reason", "SOCKET_CLOSED", "statusCode", statusCode));
    }

    public void error(UUID roomId, UUID userId, String connectionId, String code, String message) {
        eventRepository.record(roomId, userId, connectionId, "ERROR", Map.of("code", code, "message", message));
    }

    public List<ConnectionSession> findByRoom(UUID roomId) {
        if (!roomRepository.existsById(roomId)) {
            throw new NotFoundException("ROOM_NOT_FOUND", "Room not found");
        }
        return sessionRepository.findByRoomId(roomId);
    }

    public List<ConnectionSession> findByUser(UUID userId) {
        if (!userRepository.existsById(userId)) {
            throw new NotFoundException("USER_NOT_FOUND", "User not found");
        }
        return sessionRepository.findByUserId(userId);
    }
}
