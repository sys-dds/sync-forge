package com.syncforge.api.backpressure.api;

import java.util.List;
import java.util.UUID;

import com.syncforge.api.backpressure.application.ConnectionFlowControlService;
import com.syncforge.api.backpressure.model.FlowStatus;
import com.syncforge.api.room.store.RoomRepository;
import com.syncforge.api.shared.NotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rooms/{roomId}/flow/connections")
public class ConnectionFlowController {
    private final ConnectionFlowControlService flowControlService;
    private final RoomRepository roomRepository;

    public ConnectionFlowController(ConnectionFlowControlService flowControlService, RoomRepository roomRepository) {
        this.flowControlService = flowControlService;
        this.roomRepository = roomRepository;
    }

    @GetMapping
    public List<FlowStatus> list(@PathVariable UUID roomId) {
        requireRoom(roomId);
        return flowControlService.listByRoom(roomId);
    }

    @GetMapping("/{connectionId}")
    public FlowStatus get(@PathVariable UUID roomId, @PathVariable String connectionId) {
        requireRoom(roomId);
        FlowStatus status = flowControlService.require(connectionId);
        if (!status.roomId().equals(roomId)) {
            throw new NotFoundException("FLOW_STATUS_NOT_FOUND", "Connection flow status not found");
        }
        return status;
    }

    private void requireRoom(UUID roomId) {
        if (!roomRepository.existsById(roomId)) {
            throw new NotFoundException("ROOM_NOT_FOUND", "Room not found");
        }
    }
}
