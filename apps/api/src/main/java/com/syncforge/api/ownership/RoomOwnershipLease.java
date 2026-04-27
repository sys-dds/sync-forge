package com.syncforge.api.ownership;

import java.time.OffsetDateTime;
import java.util.UUID;

public record RoomOwnershipLease(
        UUID roomId,
        String ownerNodeId,
        long fencingToken,
        RoomOwnershipStatus leaseStatus,
        OffsetDateTime leaseExpiresAt,
        OffsetDateTime acquiredAt,
        OffsetDateTime renewedAt,
        OffsetDateTime releasedAt,
        String lastTakeoverReason,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public boolean isExpired(OffsetDateTime now) {
        return leaseStatus != RoomOwnershipStatus.ACTIVE || !leaseExpiresAt.isAfter(now);
    }
}
