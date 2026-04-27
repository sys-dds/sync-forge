package com.syncforge.api.runtime;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record RoomInvariantSnapshot(
        UUID roomId,
        RoomInvariantStatus status,
        OffsetDateTime checkedAt,
        long latestRoomSeq,
        long latestRevision,
        int violationCount,
        List<RoomInvariantViolation> violations
) {
}
