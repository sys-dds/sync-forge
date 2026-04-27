package com.syncforge.api.resume.model;

import java.util.UUID;

public record SnapshotRefresh(
        UUID roomId,
        UUID snapshotId,
        long minimumResumableRoomSeq,
        long snapshotRoomSeq,
        long latestRoomSeq,
        String visibleText,
        String contentChecksum
) {
}
