package com.syncforge.api.connection.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ConnectionEvent(
        UUID id,
        UUID roomId,
        UUID userId,
        String connectionId,
        String eventType,
        String eventJson,
        OffsetDateTime createdAt
) {
}
