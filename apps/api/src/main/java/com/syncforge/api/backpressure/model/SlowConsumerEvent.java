package com.syncforge.api.backpressure.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SlowConsumerEvent(
        UUID id,
        UUID roomId,
        UUID userId,
        String connectionId,
        String nodeId,
        int queuedMessages,
        int threshold,
        String decision,
        String reason,
        OffsetDateTime createdAt) {
}
