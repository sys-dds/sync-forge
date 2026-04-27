package com.syncforge.api.delivery;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record RoomEventOutboxRecord(
        UUID id,
        UUID roomId,
        long roomSeq,
        long revision,
        String operationId,
        String eventType,
        String logicalEventKey,
        Map<String, Object> payload,
        RoomEventOutboxStatus status,
        int attemptCount,
        int maxAttempts,
        OffsetDateTime nextAttemptAt,
        String lockedBy,
        OffsetDateTime lockedUntil,
        String lastError,
        String publishedStreamKey,
        String publishedStreamId,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime publishedAt,
        OffsetDateTime parkedAt,
        String ownerNodeId,
        Long fencingToken
) {
}
