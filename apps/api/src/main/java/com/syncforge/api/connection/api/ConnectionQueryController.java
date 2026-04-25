package com.syncforge.api.connection.api;

import java.util.List;
import java.util.UUID;

import com.syncforge.api.connection.application.ConnectionRegistryService;
import com.syncforge.api.shared.RequestValidator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ConnectionQueryController {
    private final ConnectionRegistryService connectionRegistryService;

    public ConnectionQueryController(ConnectionRegistryService connectionRegistryService) {
        this.connectionRegistryService = connectionRegistryService;
    }

    @GetMapping("/rooms/{roomId}/connections")
    public List<ConnectionSessionResponse> byRoom(@PathVariable String roomId) {
        UUID parsedRoomId = RequestValidator.parseUuid(roomId, "roomId");
        return connectionRegistryService.findByRoom(parsedRoomId).stream()
                .map(ConnectionSessionResponse::from)
                .toList();
    }

    @GetMapping("/users/{userId}/connections")
    public List<ConnectionSessionResponse> byUser(@PathVariable String userId) {
        UUID parsedUserId = RequestValidator.parseUuid(userId, "userId");
        return connectionRegistryService.findByUser(parsedUserId).stream()
                .map(ConnectionSessionResponse::from)
                .toList();
    }
}
