package com.syncforge.api.runtime;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.syncforge.api.delivery.RoomEventOutboxRepository;
import com.syncforge.api.delivery.RoomEventOutboxStatus;
import com.syncforge.api.documentstate.application.DocumentStateService;
import com.syncforge.api.documentstate.model.DocumentLiveState;
import com.syncforge.api.operation.store.OperationRepository;
import com.syncforge.api.ownership.RoomOwnershipLease;
import com.syncforge.api.ownership.RoomOwnershipService;
import com.syncforge.api.resume.application.ResumeWindowService;
import com.syncforge.api.resume.model.ResumeWindow;
import org.springframework.stereotype.Service;

@Service
public class RoomRuntimeHealthService {
    private final DocumentStateService documentStateService;
    private final OperationRepository operationRepository;
    private final RoomEventOutboxRepository outboxRepository;
    private final ResumeWindowService resumeWindowService;
    private final RoomOwnershipService ownershipService;
    private final RoomRuntimeControlService controlService;
    private final PoisonOperationService poisonOperationService;

    public RoomRuntimeHealthService(
            DocumentStateService documentStateService,
            OperationRepository operationRepository,
            RoomEventOutboxRepository outboxRepository,
            ResumeWindowService resumeWindowService,
            RoomOwnershipService ownershipService,
            RoomRuntimeControlService controlService,
            PoisonOperationService poisonOperationService) {
        this.documentStateService = documentStateService;
        this.operationRepository = operationRepository;
        this.outboxRepository = outboxRepository;
        this.resumeWindowService = resumeWindowService;
        this.ownershipService = ownershipService;
        this.controlService = controlService;
        this.poisonOperationService = poisonOperationService;
    }

    public RoomRuntimeHealthResponse health(UUID roomId) {
        DocumentLiveState state = documentStateService.getOrInitialize(roomId);
        ResumeWindow window = resumeWindowService.window(roomId);
        RoomRuntimeControlState control = controlService.state(roomId);
        RoomOwnershipLease lease = ownershipService.currentOwnership(roomId);
        long pending = outboxRepository.countByRoomAndStatus(roomId, RoomEventOutboxStatus.PENDING);
        long retry = outboxRepository.countByRoomAndStatus(roomId, RoomEventOutboxStatus.RETRY);
        long parked = outboxRepository.countByRoomAndStatus(roomId, RoomEventOutboxStatus.PARKED);
        long poisonCount = poisonOperationService.countQuarantined(roomId);
        RoomRuntimeStatus status = status(control, retry, parked, poisonCount);
        return new RoomRuntimeHealthResponse(
                roomId,
                status,
                state.currentRoomSeq(),
                state.currentRevision(),
                state.contentChecksum(),
                operationRepository.countByRoom(roomId),
                operationRepository.countActiveByRoom(roomId),
                operationRepository.countCompactedByRoom(roomId),
                pending,
                retry,
                parked,
                Math.max(0, state.currentRoomSeq() - outboxRepository.latestPublishedRoomSeq(roomId)),
                window.snapshotRoomSeq(),
                window.minimumResumableRoomSeq(),
                operationRepository.countActiveAfterRoomSeq(roomId, window.minimumResumableRoomSeq()),
                lease == null ? null : lease.ownerNodeId(),
                lease == null ? null : lease.fencingToken(),
                lease == null ? null : lease.leaseStatus(),
                lease == null ? null : lease.leaseExpiresAt(),
                ownershipService.latestEvent(roomId).orElse(null),
                0,
                "UNKNOWN",
                control.writesPaused(),
                control.repairRequired() || poisonCount > 0,
                control.forceResyncGeneration(),
                OffsetDateTime.now());
    }

    private RoomRuntimeStatus status(RoomRuntimeControlState control, long retry, long parked, long poisonCount) {
        if (control.repairRequired() || poisonCount > 0) {
            return RoomRuntimeStatus.REPAIR_REQUIRED;
        }
        if (control.writesPaused()) {
            return RoomRuntimeStatus.PAUSED;
        }
        if (control.forceResyncGeneration() > 0) {
            return RoomRuntimeStatus.RESYNC_REQUIRED;
        }
        if (retry > 0 || parked > 0) {
            return RoomRuntimeStatus.DEGRADED;
        }
        return RoomRuntimeStatus.HEALTHY;
    }
}
