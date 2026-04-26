package com.syncforge.api.resume.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ResumeToken(
        UUID id,
        UUID roomId,
        UUID userId,
        String connectionId,
        String clientSessionId,
        String tokenHash,
        OffsetDateTime issuedAt,
        OffsetDateTime expiresAt,
        OffsetDateTime revokedAt,
        long lastSeenRoomSeq
) {
}
