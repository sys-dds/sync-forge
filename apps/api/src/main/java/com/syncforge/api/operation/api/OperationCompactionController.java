package com.syncforge.api.operation.api;

import java.util.UUID;

import com.syncforge.api.operation.application.OperationCompactionService;
import com.syncforge.api.operation.application.OperationCompactionService.CompactionPreview;
import com.syncforge.api.operation.application.OperationCompactionService.CompactionResult;
import com.syncforge.api.room.application.RoomPermissionService;
import com.syncforge.api.shared.RequestValidator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rooms/{roomId}/operations/compaction")
public class OperationCompactionController {
    private final OperationCompactionService compactionService;
    private final RoomPermissionService permissionService;

    public OperationCompactionController(
            OperationCompactionService compactionService,
            RoomPermissionService permissionService) {
        this.compactionService = compactionService;
        this.permissionService = permissionService;
    }

    @GetMapping
    public CompactionPreview preview(@PathVariable String roomId, @RequestParam String userId) {
        UUID parsedRoomId = RequestValidator.parseUuid(roomId, "roomId");
        permissionService.requireManageMembers(parsedRoomId, RequestValidator.parseUuid(userId, "userId"));
        return compactionService.preview(parsedRoomId);
    }

    @PostMapping
    public CompactionResult compact(@PathVariable String roomId, @RequestParam String userId) {
        UUID parsedRoomId = RequestValidator.parseUuid(roomId, "roomId");
        permissionService.requireManageMembers(parsedRoomId, RequestValidator.parseUuid(userId, "userId"));
        return compactionService.compactSafeHistory(parsedRoomId);
    }
}
