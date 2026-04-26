package com.syncforge.api.operation.api;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.syncforge.api.operation.model.RoomSequence;

public record RoomSequenceResponse(UUID roomId, long currentRoomSeq, long currentRevision, OffsetDateTime updatedAt) {
    public static RoomSequenceResponse from(RoomSequence sequence) {
        return new RoomSequenceResponse(
                sequence.roomId(),
                sequence.currentRoomSeq(),
                sequence.currentRevision(),
                sequence.updatedAt());
    }
}
