package com.syncforge.api.room.application;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.syncforge.api.identity.store.UserRepository;
import com.syncforge.api.room.api.CreateRoomMembershipRequest;
import com.syncforge.api.room.model.RoomMembership;
import com.syncforge.api.room.store.RoomMembershipRepository;
import com.syncforge.api.room.store.RoomRepository;
import com.syncforge.api.shared.BadRequestException;
import com.syncforge.api.shared.NotFoundException;
import com.syncforge.api.shared.RequestValidator;
import org.springframework.stereotype.Service;

@Service
public class RoomMembershipService {
    private static final Set<String> ALLOWED_ROLES = Set.of("OWNER", "EDITOR", "VIEWER");

    private final RoomMembershipRepository membershipRepository;
    private final RoomRepository roomRepository;
    private final UserRepository userRepository;

    public RoomMembershipService(
            RoomMembershipRepository membershipRepository,
            RoomRepository roomRepository,
            UserRepository userRepository) {
        this.membershipRepository = membershipRepository;
        this.roomRepository = roomRepository;
        this.userRepository = userRepository;
    }

    public RoomMembership create(UUID roomId, CreateRoomMembershipRequest request) {
        if (request == null) {
            throw new BadRequestException("INVALID_REQUEST", "Request body is required");
        }
        if (!roomRepository.existsById(roomId)) {
            throw new NotFoundException("ROOM_NOT_FOUND", "Room not found");
        }
        UUID userId = RequestValidator.parseUuid(request.userId(), "userId");
        if (!userRepository.existsById(userId)) {
            throw new NotFoundException("USER_NOT_FOUND", "User not found");
        }
        String role = RequestValidator.requiredText(request.role(), "role").toUpperCase();
        if (!ALLOWED_ROLES.contains(role)) {
            throw new BadRequestException("INVALID_ROLE", "role must be OWNER, EDITOR, or VIEWER");
        }
        return membershipRepository.upsert(roomId, userId, role);
    }

    public List<RoomMembership> list(UUID roomId) {
        if (!roomRepository.existsById(roomId)) {
            throw new NotFoundException("ROOM_NOT_FOUND", "Room not found");
        }
        return membershipRepository.findByRoomId(roomId);
    }
}
