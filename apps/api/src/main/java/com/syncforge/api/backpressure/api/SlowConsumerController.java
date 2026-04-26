package com.syncforge.api.backpressure.api;

import java.util.List;
import java.util.UUID;

import com.syncforge.api.backpressure.application.SlowConsumerService;
import com.syncforge.api.backpressure.model.SlowConsumerEvent;
import com.syncforge.api.room.store.RoomRepository;
import com.syncforge.api.shared.NotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rooms/{roomId}/slow-consumers")
public class SlowConsumerController {
    private final SlowConsumerService slowConsumerService;
    private final RoomRepository roomRepository;

    public SlowConsumerController(SlowConsumerService slowConsumerService, RoomRepository roomRepository) {
        this.slowConsumerService = slowConsumerService;
        this.roomRepository = roomRepository;
    }

    @GetMapping
    public List<SlowConsumerEvent> list(@PathVariable UUID roomId) {
        if (!roomRepository.existsById(roomId)) {
            throw new NotFoundException("ROOM_NOT_FOUND", "Room not found");
        }
        return slowConsumerService.listByRoom(roomId);
    }
}
