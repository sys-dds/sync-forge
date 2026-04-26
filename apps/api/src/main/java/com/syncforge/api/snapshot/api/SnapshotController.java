package com.syncforge.api.snapshot.api;

import java.util.UUID;

import com.syncforge.api.shared.RequestValidator;
import com.syncforge.api.snapshot.application.SnapshotReplayService;
import com.syncforge.api.snapshot.application.SnapshotService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rooms/{roomId}/snapshots")
public class SnapshotController {
    private final SnapshotService snapshotService;
    private final SnapshotReplayService snapshotReplayService;

    public SnapshotController(SnapshotService snapshotService, SnapshotReplayService snapshotReplayService) {
        this.snapshotService = snapshotService;
        this.snapshotReplayService = snapshotReplayService;
    }

    @PostMapping
    public DocumentSnapshotResponse create(@PathVariable String roomId) {
        UUID parsedRoomId = RequestValidator.parseUuid(roomId, "roomId");
        return DocumentSnapshotResponse.from(snapshotService.createSnapshot(parsedRoomId, "MANUAL"));
    }

    @GetMapping("/latest")
    public DocumentSnapshotResponse latest(@PathVariable String roomId) {
        UUID parsedRoomId = RequestValidator.parseUuid(roomId, "roomId");
        return DocumentSnapshotResponse.from(snapshotService.getLatestSnapshot(parsedRoomId));
    }

    @PostMapping("/replay")
    public SnapshotReplayResponse replay(@PathVariable String roomId) {
        UUID parsedRoomId = RequestValidator.parseUuid(roomId, "roomId");
        return snapshotReplayService.replayFromLatestSnapshot(parsedRoomId);
    }
}
