package com.syncforge.api.backpressure.model;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

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

    @JsonProperty("pendingEventsMeaning")
    public String pendingEventsMeaning() {
        return "accepted room events not yet acknowledged through ACK_ROOM_EVENT";
    }
}
