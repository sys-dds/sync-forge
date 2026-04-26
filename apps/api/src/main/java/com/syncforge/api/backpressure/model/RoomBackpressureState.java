package com.syncforge.api.backpressure.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public record RoomBackpressureState(
        UUID roomId,
        String status,
        int pendingEvents,
        int maxPendingEvents,
        OffsetDateTime lastTriggeredAt,
        OffsetDateTime lastClearedAt,
        String reason,
        OffsetDateTime updatedAt) {
    public boolean rejecting() {
        return "REJECTING".equals(status);
    }

    public boolean warning() {
        return "WARNING".equals(status) || "REJECTING".equals(status);
    }
}
