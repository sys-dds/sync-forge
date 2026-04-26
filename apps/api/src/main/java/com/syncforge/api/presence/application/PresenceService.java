package com.syncforge.api.presence.application;

import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.syncforge.api.node.CrossNodePresenceService;
import com.syncforge.api.presence.model.PresenceConnection;
import com.syncforge.api.presence.model.UserPresence;
import com.syncforge.api.presence.store.PresenceRepository;
import com.syncforge.api.room.application.RoomPermissionService;
import com.syncforge.api.room.store.RoomRepository;
import com.syncforge.api.shared.NotFoundException;
import com.syncforge.api.shared.RequestValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class PresenceService {
    private final PresenceRepository presenceRepository;
    private final RoomRepository roomRepository;
    private final RoomPermissionService permissionService;
    private final CrossNodePresenceService crossNodePresenceService;
    private final long ttlSeconds;

    public PresenceService(
            PresenceRepository presenceRepository,
            RoomRepository roomRepository,
            RoomPermissionService permissionService,
            CrossNodePresenceService crossNodePresenceService,
            @Value("${syncforge.presence.ttl-seconds:30}") long ttlSeconds) {
        this.presenceRepository = presenceRepository;
        this.roomRepository = roomRepository;
        this.permissionService = permissionService;
        this.crossNodePresenceService = crossNodePresenceService;
        this.ttlSeconds = ttlSeconds;
    }

    public void join(
            UUID roomId,
            UUID userId,
            String connectionId,
            String websocketSessionId,
            String deviceId,
            String clientSessionId) {
        OffsetDateTime now = OffsetDateTime.now();
        presenceRepository.upsertPresent(roomId, userId, connectionId, websocketSessionId, deviceId, clientSessionId,
                now, now.plusSeconds(ttlSeconds));
        presenceRepository.recomputeUserPresence(roomId, userId, now);
        crossNodePresenceService.updateLocal(roomId, userId);
    }

    public void leave(UUID roomId, UUID userId, String connectionId, String reason) {
        OffsetDateTime now = OffsetDateTime.now();
        presenceRepository.markLeft(connectionId, reason, now);
        presenceRepository.recomputeUserPresence(roomId, userId, now);
        crossNodePresenceService.updateLocal(roomId, userId);
    }

    public void heartbeat(UUID roomId, UUID userId, String connectionId) {
        OffsetDateTime now = OffsetDateTime.now();
        presenceRepository.refresh(connectionId, now, now.plusSeconds(ttlSeconds));
        presenceRepository.recomputeUserPresence(roomId, userId, now);
        crossNodePresenceService.updateLocal(roomId, userId);
    }

    public int expireStalePresence(OffsetDateTime now) {
        OffsetDateTime effectiveNow = now == null ? OffsetDateTime.now() : now;
        List<PresenceConnection> expired = presenceRepository.expireStale(effectiveNow);
        Set<RoomUser> affected = new LinkedHashSet<>();
        for (PresenceConnection connection : expired) {
            affected.add(new RoomUser(connection.roomId(), connection.userId()));
        }
        for (RoomUser roomUser : affected) {
            presenceRepository.recomputeUserPresence(roomUser.roomId(), roomUser.userId(), effectiveNow);
            crossNodePresenceService.updateLocal(roomUser.roomId(), roomUser.userId());
        }
        return expired.size();
    }

    public List<UserPresence> findRoomPresence(UUID roomId, String requesterUserId) {
        requireRoom(roomId);
        requireViewIfProvided(roomId, requesterUserId);
        return presenceRepository.findUserPresenceByRoom(roomId);
    }

    public List<PresenceConnection> findRoomPresenceConnections(UUID roomId, String requesterUserId) {
        requireRoom(roomId);
        requireViewIfProvided(roomId, requesterUserId);
        return presenceRepository.findConnectionsByRoom(roomId);
    }

    private void requireViewIfProvided(UUID roomId, String requesterUserId) {
        if (requesterUserId == null || requesterUserId.isBlank()) {
            return;
        }
        UUID userId = RequestValidator.parseUuid(requesterUserId, "X-User-Id");
        permissionService.requireView(roomId, userId);
    }

    private void requireRoom(UUID roomId) {
        if (!roomRepository.existsById(roomId)) {
            throw new NotFoundException("ROOM_NOT_FOUND", "Room not found");
        }
    }

    private record RoomUser(UUID roomId, UUID userId) {
    }
}
