package com.syncforge.api.room.application;

import java.util.Set;
import java.util.UUID;

import com.syncforge.api.room.model.RoomMembership;
import com.syncforge.api.room.store.RoomMembershipRepository;
import com.syncforge.api.room.store.RoomRepository;
import com.syncforge.api.shared.ForbiddenException;
import com.syncforge.api.shared.NotFoundException;
import org.springframework.stereotype.Service;

@Service
public class RoomPermissionService {
    private final RoomMembershipRepository membershipRepository;
    private final RoomRepository roomRepository;

    public RoomPermissionService(RoomMembershipRepository membershipRepository, RoomRepository roomRepository) {
        this.membershipRepository = membershipRepository;
        this.roomRepository = roomRepository;
    }

    public boolean canJoin(UUID roomId, UUID userId) {
        return hasRole(roomId, userId, Set.of("OWNER", "EDITOR", "VIEWER"));
    }

    public boolean canView(UUID roomId, UUID userId) {
        return hasRole(roomId, userId, Set.of("OWNER", "EDITOR", "VIEWER"));
    }

    public boolean canEdit(UUID roomId, UUID userId) {
        return hasRole(roomId, userId, Set.of("OWNER", "EDITOR"));
    }

    public boolean canManageMembers(UUID roomId, UUID userId) {
        return hasRole(roomId, userId, Set.of("OWNER"));
    }

    public void requireJoin(UUID roomId, UUID userId) {
        requireRoom(roomId);
        if (!canJoin(roomId, userId)) {
            throw new ForbiddenException("ROOM_ACCESS_DENIED", "User is not allowed to join room");
        }
    }

    public void requireView(UUID roomId, UUID userId) {
        requireRoom(roomId);
        if (!canView(roomId, userId)) {
            throw new ForbiddenException("ROOM_ACCESS_DENIED", "User is not allowed to view room");
        }
    }

    public void requireEdit(UUID roomId, UUID userId) {
        requireRoom(roomId);
        if (!canEdit(roomId, userId)) {
            throw new ForbiddenException("ROOM_ACCESS_DENIED", "User is not allowed to edit room");
        }
    }

    public void requireManageMembers(UUID roomId, UUID userId) {
        requireRoom(roomId);
        if (!canManageMembers(roomId, userId)) {
            throw new ForbiddenException("ROOM_ACCESS_DENIED", "User is not allowed to manage room members");
        }
    }

    private boolean hasRole(UUID roomId, UUID userId, Set<String> allowedRoles) {
        return membershipRepository.findByRoomAndUser(roomId, userId)
                .filter(RoomMembership::active)
                .map(RoomMembership::role)
                .filter(allowedRoles::contains)
                .isPresent();
    }

    private void requireRoom(UUID roomId) {
        if (!roomRepository.existsById(roomId)) {
            throw new NotFoundException("ROOM_NOT_FOUND", "Room not found");
        }
    }
}
