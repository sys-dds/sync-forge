package com.syncforge.api.runtime;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record OwnershipFencingAuditResponse(
        UUID roomId,
        RoomInvariantStatus status,
        OffsetDateTime checkedAt,
        int violationCount,
        List<RoomInvariantViolation> violations
) {
}
