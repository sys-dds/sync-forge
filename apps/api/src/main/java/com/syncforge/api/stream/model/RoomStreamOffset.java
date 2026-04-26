package com.syncforge.api.stream.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public record RoomStreamOffset(
        UUID roomId,
        String nodeId,
        String streamKey,
        String lastStreamId,
        long lastRoomSeq,
        String status,
        Long expectedRoomSeq,
        Long observedRoomSeq,
        OffsetDateTime lastGapAt,
        String lastError,
        OffsetDateTime updatedAt) {
}
