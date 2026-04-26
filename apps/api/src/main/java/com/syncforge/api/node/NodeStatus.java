package com.syncforge.api.node;

import java.time.OffsetDateTime;

public record NodeStatus(
        String nodeId,
        String status,
        OffsetDateTime startedAt,
        OffsetDateTime lastSeenAt,
        long heartbeatTtlSeconds) {
}
