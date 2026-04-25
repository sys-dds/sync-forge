package com.syncforge.api.room.api;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.syncforge.api.room.model.Room;

public record RoomResponse(
        UUID id,
        UUID workspaceId,
        UUID documentId,
        String roomKey,
        String roomType,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static RoomResponse from(Room room) {
        return new RoomResponse(
                room.id(),
                room.workspaceId(),
                room.documentId(),
                room.roomKey(),
                room.roomType(),
                room.status(),
                room.createdAt(),
                room.updatedAt());
    }
}
