package com.syncforge.api.snapshot.api;

import java.util.UUID;

import com.syncforge.api.room.application.RoomPermissionService;
import com.syncforge.api.shared.RequestValidator;
import com.syncforge.api.snapshot.application.SnapshotReplayService;
import com.syncforge.api.snapshot.application.SnapshotService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rooms/{roomId}/snapshots")
public class SnapshotController {
    private final SnapshotService snapshotService;
    private final SnapshotReplayService snapshotReplayService;
    private final RoomPermissionService permissionService;

    public SnapshotController(
            SnapshotService snapshotService,
            SnapshotReplayService snapshotReplayService,
            RoomPermissionService permissionService) {
        this.snapshotService = snapshotService;
        this.snapshotReplayService = snapshotReplayService;
        this.permissionService = permissionService;
    }

    @PostMapping
    public DocumentSnapshotResponse create(@PathVariable String roomId, @RequestParam(required = false) String userId) {
        UUID parsedRoomId = RequestValidator.parseUuid(roomId, "roomId");
        requireViewIfPresent(parsedRoomId, userId);
        return DocumentSnapshotResponse.from(snapshotService.createSnapshot(parsedRoomId, "MANUAL"));
    }

    @GetMapping("/latest")
    public DocumentSnapshotResponse latest(@PathVariable String roomId, @RequestParam(required = false) String userId) {
        UUID parsedRoomId = RequestValidator.parseUuid(roomId, "roomId");
        requireViewIfPresent(parsedRoomId, userId);
        return DocumentSnapshotResponse.from(snapshotService.getLatestSnapshot(parsedRoomId));
    }

    @PostMapping("/replay")
    public SnapshotReplayResponse replay(@PathVariable String roomId, @RequestParam(required = false) String userId) {
        UUID parsedRoomId = RequestValidator.parseUuid(roomId, "roomId");
        requireViewIfPresent(parsedRoomId, userId);
        return snapshotReplayService.replayFromLatestSnapshot(parsedRoomId);
    }

    private void requireViewIfPresent(UUID roomId, String userId) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        permissionService.requireView(roomId, RequestValidator.parseUuid(userId, "userId"));
    }
}
