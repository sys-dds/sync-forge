package com.syncforge.api.stream.model;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record RoomStreamEvent(
        String eventId,
        UUID roomId,
        long roomSeq,
        long revision,
        String operationId,
        UUID userId,
        long clientSeq,
        String operationType,
        Map<String, Object> operation,
        boolean transformed,
        String producedByNodeId,
        OffsetDateTime createdAt) {
}
