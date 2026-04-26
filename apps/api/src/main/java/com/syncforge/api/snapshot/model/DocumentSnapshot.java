package com.syncforge.api.snapshot.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public record DocumentSnapshot(
        UUID id,
        UUID roomId,
        UUID documentId,
        long roomSeq,
        long revision,
        String contentText,
        String contentChecksum,
        String snapshotReason,
        OffsetDateTime createdAt
) {
}
