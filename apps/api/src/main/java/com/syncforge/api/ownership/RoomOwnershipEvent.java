package com.syncforge.api.ownership;

import java.time.OffsetDateTime;
import java.util.UUID;

public record RoomOwnershipEvent(
        UUID id,
        UUID roomId,
        String nodeId,
        Long fencingToken,
        String eventType,
        String reason,
        String previousOwnerNodeId,
        Long previousFencingToken,
        OffsetDateTime createdAt
) {
}
