package com.syncforge.api.backpressure.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SessionQuarantine(
        UUID id,
        UUID roomId,
        UUID userId,
        String connectionId,
        String clientSessionId,
        String nodeId,
        String reason,
        OffsetDateTime startedAt,
        OffsetDateTime expiresAt,
        OffsetDateTime releasedAt) {
    public boolean active(OffsetDateTime now) {
        return releasedAt == null && expiresAt.isAfter(now);
    }
}
