package com.syncforge.api.resume.model;

import java.util.UUID;

public record ResumeWindow(
        UUID roomId,
        long minimumResumableRoomSeq,
        long snapshotRoomSeq,
        long latestRoomSeq
) {
}
