package com.syncforge.api.runtime;

import java.util.UUID;

public record RoomDeliveryRuntimeResponse(
        UUID roomId,
        long outboxPendingCount,
        long outboxRetryCount,
        long outboxPublishedCount,
        Long oldestPendingAgeMs,
        long latestPublishedRoomSeq,
        long latestAcceptedRoomSeq,
        long unpublishedAcceptedCount,
        long nodeRoomSubscriptionCount,
        String deliveryStatus
) {
}
