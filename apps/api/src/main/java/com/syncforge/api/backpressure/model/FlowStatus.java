package com.syncforge.api.backpressure.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public record FlowStatus(
        String connectionId,
        UUID roomId,
        UUID userId,
        String websocketSessionId,
        String nodeId,
        String status,
        int queuedMessages,
        int maxQueuedMessages,
        OffsetDateTime lastSendStartedAt,
        OffsetDateTime lastSendCompletedAt,
        String lastSendError,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
