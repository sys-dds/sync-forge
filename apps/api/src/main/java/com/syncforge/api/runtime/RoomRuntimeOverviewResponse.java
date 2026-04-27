package com.syncforge.api.runtime;

import java.util.UUID;

import com.syncforge.api.operation.application.OperationCompactionService.CompactionPreview;
import com.syncforge.api.resume.model.ResumeWindow;

public record RoomRuntimeOverviewResponse(
        UUID roomId,
        RoomRuntimeHealthResponse health,
        RoomInvariantStatus invariantStatus,
        RoomDeliveryRuntimeResponse delivery,
        Object ownershipStatus,
        ResumeWindow resumeWindow,
        long snapshotRoomSeq,
        CompactionPreview compaction,
        RoomRuntimeControlState controlState,
        long poisonOperationCount,
        boolean repairRequired,
        RecommendedRuntimeAction recommendedAction
) {
}
