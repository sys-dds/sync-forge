package com.syncforge.api.backpressure.api;

import java.util.UUID;

import com.syncforge.api.backpressure.application.BackpressureService;
import com.syncforge.api.backpressure.model.RoomBackpressureState;
import com.syncforge.api.room.store.RoomRepository;
import com.syncforge.api.shared.NotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rooms/{roomId}/backpressure")
public class BackpressureController {
    private final BackpressureService backpressureService;
    private final RoomRepository roomRepository;

    public BackpressureController(BackpressureService backpressureService, RoomRepository roomRepository) {
        this.backpressureService = backpressureService;
        this.roomRepository = roomRepository;
    }

    @GetMapping
    public RoomBackpressureState get(@PathVariable UUID roomId) {
        if (!roomRepository.existsById(roomId)) {
            throw new NotFoundException("ROOM_NOT_FOUND", "Room not found");
        }
        return backpressureService.current(roomId);
    }
}
