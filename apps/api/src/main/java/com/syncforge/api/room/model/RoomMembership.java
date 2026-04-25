package com.syncforge.api.room.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public record RoomMembership(
        UUID id,
        UUID roomId,
        UUID userId,
        String role,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public boolean active() {
        return "ACTIVE".equals(status);
    }
}
