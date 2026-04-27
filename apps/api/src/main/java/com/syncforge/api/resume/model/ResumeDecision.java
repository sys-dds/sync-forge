package com.syncforge.api.resume.model;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ResumeDecision(
        ResumeDecisionType decision,
        UUID roomId,
        long requestedFromRoomSeq,
        long fromRoomSeq,
        long toRoomSeq,
        long minimumResumableRoomSeq,
        long snapshotRoomSeq,
        long latestRoomSeq,
        int returnedOperationCount,
        String reason,
        List<Map<String, Object>> operations
) {
}
