package com.syncforge.api.resume.model;

import java.util.UUID;

public record SnapshotRefresh(
        UUID roomId,
        UUID snapshotId,
        long minimumResumableRoomSeq,
        long snapshotRoomSeq,
        long latestRoomSeq,
        long baselineRoomSeq,
        String visibleText,
        String contentChecksum,
        String refreshReason,
        long nextResumeFromRoomSeq
) {
}
