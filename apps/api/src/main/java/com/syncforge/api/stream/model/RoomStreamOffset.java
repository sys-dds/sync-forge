package com.syncforge.api.stream.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public record RoomStreamOffset(
        UUID roomId,
        String nodeId,
        String streamKey,
        String lastStreamId,
        long lastRoomSeq,
        OffsetDateTime updatedAt) {
}
