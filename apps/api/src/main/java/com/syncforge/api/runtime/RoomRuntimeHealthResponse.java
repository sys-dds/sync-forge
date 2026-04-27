package com.syncforge.api.runtime;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.syncforge.api.ownership.RoomOwnershipEvent;
import com.syncforge.api.ownership.RoomOwnershipStatus;

public record RoomRuntimeHealthResponse(
        UUID roomId,
        RoomRuntimeStatus status,
        long latestRoomSeq,
        long latestRevision,
        String visibleTextChecksum,
        long operationCount,
        long activeOperationCount,
        long compactedOperationCount,
        long outboxPendingCount,
        long outboxRetryCount,
        long outboxParkedCount,
        long streamLag,
        long snapshotRoomSeq,
        long minimumResumableRoomSeq,
        long activeTailCount,
        String currentOwnerNodeId,
        Long fencingToken,
        RoomOwnershipStatus leaseStatus,
        OffsetDateTime leaseExpiresAt,
        RoomOwnershipEvent lastOwnershipEvent,
        long slowConsumerCount,
        String backpressureStatus,
        boolean paused,
        boolean repairRequired,
        long forceResyncGeneration,
        OffsetDateTime generatedAt
) {
}
