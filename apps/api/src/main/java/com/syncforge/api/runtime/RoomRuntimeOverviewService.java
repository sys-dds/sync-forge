package com.syncforge.api.runtime;

import java.util.UUID;

import com.syncforge.api.operation.application.OperationCompactionService;
import com.syncforge.api.ownership.RoomOwnershipService;
import com.syncforge.api.resume.application.ResumeWindowService;
import org.springframework.stereotype.Service;

@Service
public class RoomRuntimeOverviewService {
    private final RoomRuntimeHealthService healthService;
    private final RoomConsistencyVerifier verifier;
    private final RoomDeliveryRuntimeService deliveryRuntimeService;
    private final RoomOwnershipService ownershipService;
    private final ResumeWindowService resumeWindowService;
    private final OperationCompactionService compactionService;
    private final RoomRuntimeControlService controlService;
    private final PoisonOperationService poisonOperationService;

    public RoomRuntimeOverviewService(
            RoomRuntimeHealthService healthService,
            RoomConsistencyVerifier verifier,
            RoomDeliveryRuntimeService deliveryRuntimeService,
            RoomOwnershipService ownershipService,
            ResumeWindowService resumeWindowService,
            OperationCompactionService compactionService,
            RoomRuntimeControlService controlService,
            PoisonOperationService poisonOperationService) {
        this.healthService = healthService;
        this.verifier = verifier;
        this.deliveryRuntimeService = deliveryRuntimeService;
        this.ownershipService = ownershipService;
        this.resumeWindowService = resumeWindowService;
        this.compactionService = compactionService;
        this.controlService = controlService;
        this.poisonOperationService = poisonOperationService;
    }

    public RoomRuntimeOverviewResponse overview(UUID roomId) {
        RoomRuntimeHealthResponse health = healthService.health(roomId);
        RoomInvariantSnapshot invariants = verifier.verify(roomId);
        RoomDeliveryRuntimeResponse delivery = deliveryRuntimeService.status(roomId);
        var window = resumeWindowService.window(roomId);
        var control = controlService.state(roomId);
        long poisonCount = poisonOperationService.countQuarantined(roomId);
        return new RoomRuntimeOverviewResponse(
                roomId,
                health,
                invariants.status(),
                delivery,
                ownershipService.currentOwnership(roomId),
                window,
                window.snapshotRoomSeq(),
                compactionService.preview(roomId),
                control,
                poisonCount,
                control.repairRequired() || poisonCount > 0,
                recommended(health, invariants, delivery, window.snapshotRoomSeq()));
    }

    private RecommendedRuntimeAction recommended(
            RoomRuntimeHealthResponse health,
            RoomInvariantSnapshot invariants,
            RoomDeliveryRuntimeResponse delivery,
            long snapshotRoomSeq) {
        if (invariants.status() == RoomInvariantStatus.FAIL || health.status() == RoomRuntimeStatus.REPAIR_REQUIRED) {
            return RecommendedRuntimeAction.PAUSE_AND_REPAIR;
        }
        if ("BACKLOGGED".equals(delivery.deliveryStatus()) || "RETRYING".equals(delivery.deliveryStatus())) {
            return RecommendedRuntimeAction.DRAIN_OUTBOX;
        }
        if (health.status() == RoomRuntimeStatus.RESYNC_REQUIRED) {
            return RecommendedRuntimeAction.FORCE_RESYNC;
        }
        if (snapshotRoomSeq == 0 && health.latestRoomSeq() > 0) {
            return RecommendedRuntimeAction.SNAPSHOT_REFRESH_REQUIRED;
        }
        return RecommendedRuntimeAction.NONE;
    }
}
