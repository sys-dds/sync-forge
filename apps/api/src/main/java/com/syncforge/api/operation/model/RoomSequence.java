package com.syncforge.api.operation.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public record RoomSequence(UUID roomId, long currentRoomSeq, long currentRevision, OffsetDateTime updatedAt) {
}
