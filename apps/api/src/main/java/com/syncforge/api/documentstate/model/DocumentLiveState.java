package com.syncforge.api.documentstate.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public record DocumentLiveState(
        UUID id,
        UUID roomId,
        UUID documentId,
        long currentRoomSeq,
        long currentRevision,
        String contentText,
        String contentChecksum,
        UUID lastOperationId,
        UUID rebuiltFromSnapshotId,
        OffsetDateTime rebuiltAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
