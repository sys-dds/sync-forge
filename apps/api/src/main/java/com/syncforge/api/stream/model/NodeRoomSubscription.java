package com.syncforge.api.stream.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public record NodeRoomSubscription(
        String nodeId,
        UUID roomId,
        int localConnectionCount,
        OffsetDateTime subscribedAt,
        OffsetDateTime lastEventAt) {
}
