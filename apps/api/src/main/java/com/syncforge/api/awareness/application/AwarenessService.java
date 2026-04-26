package com.syncforge.api.awareness.application;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import com.syncforge.api.awareness.model.AwarenessState;
import com.syncforge.api.awareness.store.AwarenessRepository;
import com.syncforge.api.room.application.RoomPermissionService;
import com.syncforge.api.room.store.RoomRepository;
import com.syncforge.api.shared.BadRequestException;
import com.syncforge.api.shared.NotFoundException;
import com.syncforge.api.shared.RequestValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AwarenessService {
    private final AwarenessRepository awarenessRepository;
    private final RoomRepository roomRepository;
    private final RoomPermissionService permissionService;
    private final long ttlSeconds;

    public AwarenessService(
            AwarenessRepository awarenessRepository,
            RoomRepository roomRepository,
            RoomPermissionService permissionService,
            @Value("${syncforge.awareness.ttl-seconds:30}") long ttlSeconds) {
        this.awarenessRepository = awarenessRepository;
        this.roomRepository = roomRepository;
        this.permissionService = permissionService;
        this.ttlSeconds = ttlSeconds;
    }

    public AwarenessState updateCursor(UUID roomId, UUID userId, String connectionId, Integer cursorPosition, Map<String, Object> metadata) {
        if (cursorPosition == null || cursorPosition < 0) {
            throw new BadRequestException("INVALID_AWARENESS", "cursorPosition must be non-negative");
        }
        permissionService.requireJoin(roomId, userId);
        OffsetDateTime now = OffsetDateTime.now();
        return awarenessRepository.upsertCursor(roomId, userId, connectionId, cursorPosition, metadata, now, now.plusSeconds(ttlSeconds));
    }

    public AwarenessState updateSelection(
            UUID roomId,
            UUID userId,
            String connectionId,
            Integer anchorPosition,
            Integer focusPosition,
            Map<String, Object> metadata) {
        if (anchorPosition == null || anchorPosition < 0 || focusPosition == null || focusPosition < 0) {
            throw new BadRequestException("INVALID_AWARENESS", "anchorPosition and focusPosition must be non-negative");
        }
        permissionService.requireJoin(roomId, userId);
        OffsetDateTime now = OffsetDateTime.now();
        return awarenessRepository.upsertSelection(roomId, userId, connectionId, anchorPosition, focusPosition, metadata,
                now, now.plusSeconds(ttlSeconds));
    }

    public int expireStaleAwareness(OffsetDateTime now) {
        return awarenessRepository.expireStale(now == null ? OffsetDateTime.now() : now);
    }

    public java.util.List<AwarenessState> findRoomAwareness(UUID roomId, String requesterUserId) {
        requireRoom(roomId);
        requireViewIfProvided(roomId, requesterUserId);
        return awarenessRepository.findActiveByRoom(roomId);
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
}
