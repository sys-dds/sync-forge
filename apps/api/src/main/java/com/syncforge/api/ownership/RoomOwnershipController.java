package com.syncforge.api.ownership;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.syncforge.api.room.application.RoomPermissionService;
import com.syncforge.api.shared.RequestValidator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rooms/{roomId}/ownership")
public class RoomOwnershipController {
    private final RoomOwnershipService ownershipService;
    private final RoomPermissionService permissionService;

    public RoomOwnershipController(RoomOwnershipService ownershipService, RoomPermissionService permissionService) {
        this.ownershipService = ownershipService;
        this.permissionService = permissionService;
    }

    @GetMapping
    public RoomOwnershipStatusResponse status(@PathVariable String roomId, @RequestParam String userId) {
        UUID parsedRoomId = RequestValidator.parseUuid(roomId, "roomId");
        permissionService.requireManageMembers(parsedRoomId, RequestValidator.parseUuid(userId, "userId"));
        OffsetDateTime now = OffsetDateTime.now();
        return RoomOwnershipStatusResponse.from(
                ownershipService.currentOwnership(parsedRoomId),
                ownershipService.latestEvent(parsedRoomId).orElse(null),
                now);
    }
}
