package com.syncforge.api.snapshot.api;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.syncforge.api.snapshot.model.DocumentSnapshot;

public record DocumentSnapshotResponse(
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
    public static DocumentSnapshotResponse from(DocumentSnapshot snapshot) {
        return new DocumentSnapshotResponse(
                snapshot.id(),
                snapshot.roomId(),
                snapshot.documentId(),
                snapshot.roomSeq(),
                snapshot.revision(),
                snapshot.contentText(),
                snapshot.contentChecksum(),
                snapshot.snapshotReason(),
                snapshot.createdAt());
    }
}
