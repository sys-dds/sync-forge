package com.syncforge.api.ownership;

import java.time.OffsetDateTime;
import java.util.UUID;

public record RoomOwnershipStatusResponse(
        UUID roomId,
        String ownerNodeId,
        Long fencingToken,
        String leaseStatus,
        OffsetDateTime leaseExpiresAt,
        OffsetDateTime acquiredAt,
        OffsetDateTime renewedAt,
        RoomOwnershipEvent latestOwnershipEvent,
        boolean isExpired,
        OffsetDateTime serverNow
) {
    public static RoomOwnershipStatusResponse from(RoomOwnershipLease lease, RoomOwnershipEvent event, OffsetDateTime now) {
        if (lease == null) {
            return new RoomOwnershipStatusResponse(null, null, null, null, null, null, null, event, true, now);
        }
        return new RoomOwnershipStatusResponse(
                lease.roomId(),
                lease.ownerNodeId(),
                lease.fencingToken(),
                lease.leaseStatus().name(),
                lease.leaseExpiresAt(),
                lease.acquiredAt(),
                lease.renewedAt(),
                event,
                lease.isExpired(now),
                now);
    }
}
