package com.syncforge.api.node.api;

import java.util.List;
import java.util.UUID;

import com.syncforge.api.node.CrossNodePresenceService;
import com.syncforge.api.node.CrossNodePresenceState;
import com.syncforge.api.room.store.RoomRepository;
import com.syncforge.api.shared.NotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rooms/{roomId}/presence/nodes")
public class CrossNodePresenceController {
    private final CrossNodePresenceService crossNodePresenceService;
    private final RoomRepository roomRepository;

    public CrossNodePresenceController(CrossNodePresenceService crossNodePresenceService, RoomRepository roomRepository) {
        this.crossNodePresenceService = crossNodePresenceService;
        this.roomRepository = roomRepository;
    }

    @GetMapping
    public List<CrossNodePresenceState> list(@PathVariable UUID roomId) {
        if (!roomRepository.existsById(roomId)) {
            throw new NotFoundException("ROOM_NOT_FOUND", "Room not found");
        }
        return crossNodePresenceService.listByRoom(roomId);
    }
}
