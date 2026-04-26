package com.syncforge.api.backpressure.api;

import java.util.List;
import java.util.UUID;

import com.syncforge.api.backpressure.application.SessionQuarantineService;
import com.syncforge.api.backpressure.model.SessionQuarantine;
import com.syncforge.api.room.store.RoomRepository;
import com.syncforge.api.shared.NotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rooms/{roomId}/quarantines")
public class SessionQuarantineController {
    private final SessionQuarantineService quarantineService;
    private final RoomRepository roomRepository;

    public SessionQuarantineController(SessionQuarantineService quarantineService, RoomRepository roomRepository) {
        this.quarantineService = quarantineService;
        this.roomRepository = roomRepository;
    }

    @GetMapping
    public List<SessionQuarantine> list(@PathVariable UUID roomId) {
        if (!roomRepository.existsById(roomId)) {
            throw new NotFoundException("ROOM_NOT_FOUND", "Room not found");
        }
        return quarantineService.listByRoom(roomId);
    }
}
