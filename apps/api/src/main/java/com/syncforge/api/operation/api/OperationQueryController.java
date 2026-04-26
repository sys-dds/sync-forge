package com.syncforge.api.operation.api;

import java.util.List;
import java.util.UUID;

import com.syncforge.api.operation.store.OperationAttemptRepository;
import com.syncforge.api.operation.store.OperationRepository;
import com.syncforge.api.operation.store.RoomSequenceRepository;
import com.syncforge.api.room.application.RoomPermissionService;
import com.syncforge.api.room.store.RoomRepository;
import com.syncforge.api.shared.NotFoundException;
import com.syncforge.api.shared.RequestValidator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rooms/{roomId}")
public class OperationQueryController {
    private final OperationRepository operationRepository;
    private final OperationAttemptRepository attemptRepository;
    private final RoomSequenceRepository sequenceRepository;
    private final RoomRepository roomRepository;
    private final RoomPermissionService permissionService;

    public OperationQueryController(
            OperationRepository operationRepository,
            OperationAttemptRepository attemptRepository,
            RoomSequenceRepository sequenceRepository,
            RoomRepository roomRepository,
            RoomPermissionService permissionService) {
        this.operationRepository = operationRepository;
        this.attemptRepository = attemptRepository;
        this.sequenceRepository = sequenceRepository;
        this.roomRepository = roomRepository;
        this.permissionService = permissionService;
    }

    @GetMapping("/operations")
    public List<OperationResponse> operations(
            @PathVariable String roomId,
            @RequestHeader(value = "X-User-Id", required = false) String requesterUserId) {
        UUID parsedRoomId = parseAndAuthorize(roomId, requesterUserId);
        return operationRepository.findByRoom(parsedRoomId).stream()
                .map(OperationResponse::from)
                .toList();
    }

    @GetMapping("/operation-attempts")
    public List<OperationAttemptResponse> operationAttempts(
            @PathVariable String roomId,
            @RequestHeader(value = "X-User-Id", required = false) String requesterUserId) {
        UUID parsedRoomId = parseAndAuthorize(roomId, requesterUserId);
        return attemptRepository.findByRoom(parsedRoomId).stream()
                .map(OperationAttemptResponse::from)
                .toList();
    }

    @GetMapping("/sequence")
    public RoomSequenceResponse sequence(
            @PathVariable String roomId,
            @RequestHeader(value = "X-User-Id", required = false) String requesterUserId) {
        UUID parsedRoomId = parseAndAuthorize(roomId, requesterUserId);
        return RoomSequenceResponse.from(sequenceRepository.find(parsedRoomId));
    }

    private UUID parseAndAuthorize(String roomId, String requesterUserId) {
        UUID parsedRoomId = RequestValidator.parseUuid(roomId, "roomId");
        if (!roomRepository.existsById(parsedRoomId)) {
            throw new NotFoundException("ROOM_NOT_FOUND", "Room not found");
        }
        if (requesterUserId != null && !requesterUserId.isBlank()) {
            UUID requester = RequestValidator.parseUuid(requesterUserId, "X-User-Id");
            permissionService.requireView(parsedRoomId, requester);
        }
        return parsedRoomId;
    }
}
