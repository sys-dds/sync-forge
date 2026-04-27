package com.syncforge.api.runtime;

import java.time.OffsetDateTime;
import java.util.UUID;

public record PoisonOperationRecord(
        UUID id,
        UUID roomId,
        String operationId,
        Long roomSeq,
        String reason,
        int failureCount,
        OffsetDateTime firstSeenAt,
        OffsetDateTime lastSeenAt,
        String status
) {
}
