package com.syncforge.api.ratelimit.api;

import java.util.List;
import java.util.UUID;

import com.syncforge.api.ratelimit.application.OperationRateLimitService;
import com.syncforge.api.ratelimit.model.RateLimitDecision;
import com.syncforge.api.room.store.RoomRepository;
import com.syncforge.api.shared.NotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rooms/{roomId}/rate-limit-events")
public class OperationRateLimitController {
    private final OperationRateLimitService rateLimitService;
    private final RoomRepository roomRepository;

    public OperationRateLimitController(OperationRateLimitService rateLimitService, RoomRepository roomRepository) {
        this.rateLimitService = rateLimitService;
        this.roomRepository = roomRepository;
    }

    @GetMapping
    public List<RateLimitDecision> list(@PathVariable UUID roomId) {
        if (!roomRepository.existsById(roomId)) {
            throw new NotFoundException("ROOM_NOT_FOUND", "Room not found");
        }
        return rateLimitService.listByRoom(roomId);
    }
}
