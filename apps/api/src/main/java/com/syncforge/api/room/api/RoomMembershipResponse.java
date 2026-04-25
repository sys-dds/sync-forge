package com.syncforge.api.room.api;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.syncforge.api.room.model.RoomMembership;

public record RoomMembershipResponse(
        UUID id,
        UUID roomId,
        UUID userId,
        String role,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static RoomMembershipResponse from(RoomMembership membership) {
        return new RoomMembershipResponse(
                membership.id(),
                membership.roomId(),
                membership.userId(),
                membership.role(),
                membership.status(),
                membership.createdAt(),
                membership.updatedAt());
    }
}
