package com.syncforge.api.runtime;

import java.util.UUID;

import com.syncforge.api.documentstate.application.DocumentStateService;
import com.syncforge.api.room.application.RoomPermissionService;
import com.syncforge.api.snapshot.api.SnapshotReplayResponse;
import com.syncforge.api.snapshot.application.SnapshotReplayService;
import com.syncforge.api.snapshot.model.DocumentSnapshot;
import com.syncforge.api.snapshot.store.SnapshotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoomRepairService {
    private final RoomPermissionService permissionService;
    private final RoomRuntimeControlService controlService;
    private final SnapshotRepository snapshotRepository;
    private final SnapshotReplayService snapshotReplayService;
    private final DocumentStateService documentStateService;
    private final PoisonOperationService poisonOperationService;

    public RoomRepairService(
            RoomPermissionService permissionService,
            RoomRuntimeControlService controlService,
            SnapshotRepository snapshotRepository,
            SnapshotReplayService snapshotReplayService,
            DocumentStateService documentStateService,
            PoisonOperationService poisonOperationService) {
        this.permissionService = permissionService;
        this.controlService = controlService;
        this.snapshotRepository = snapshotRepository;
        this.snapshotReplayService = snapshotReplayService;
        this.documentStateService = documentStateService;
        this.poisonOperationService = poisonOperationService;
    }

    @Transactional
    public RoomRepairRebuildResponse rebuildState(UUID roomId, UUID actorUserId, String reason) {
        permissionService.requireManageMembers(roomId, actorUserId);
        RoomRuntimeControlState control = controlService.state(roomId);
        if (!control.writesPaused()) {
            controlService.pauseWrites(roomId, actorUserId, "REPAIR_REBUILD_PAUSED_WRITES");
        }
        DocumentSnapshot snapshot = snapshotRepository.findLatest(roomId).orElse(null);
        if (snapshot != null) {
            SnapshotReplayResponse replay = snapshotReplayService.replayFromLatestSnapshot(roomId);
            controlService.clearRepairRequired(roomId, actorUserId, "REBUILD_SUCCEEDED");
            poisonOperationService.clear(roomId);
            controlService.recordRebuild(roomId, actorUserId, reason);
            return new RoomRepairRebuildResponse(
                    UUID.randomUUID(),
                    roomId,
                    replay.snapshotRoomSeq(),
                    replay.tailOperationsReplayed(),
                    replay.replayedToRoomSeq(),
                    replay.resultingChecksum(),
                    "COMPLETED");
        }
        DocumentStateService.RebuildResult rebuilt = documentStateService.rebuildFromOperationLog(roomId);
        controlService.clearRepairRequired(roomId, actorUserId, "REBUILD_SUCCEEDED");
        poisonOperationService.clear(roomId);
        controlService.recordRebuild(roomId, actorUserId, reason);
        return new RoomRepairRebuildResponse(
                UUID.randomUUID(),
                roomId,
                0,
                rebuilt.operationsReplayed(),
                rebuilt.state().currentRoomSeq(),
                rebuilt.state().contentChecksum(),
                "COMPLETED");
    }
}
