package com.syncforge.api.protocol;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

import com.syncforge.api.capability.ClientCapability;

public record ProtocolSession(
        String connectionId,
        String websocketSessionId,
        UUID roomId,
        UUID userId,
        String clientId,
        String deviceId,
        String clientSessionId,
        Integer requestedProtocolVersion,
        int negotiatedProtocolVersion,
        int serverPreferredProtocolVersion,
        boolean legacyDefaultApplied,
        Set<ClientCapability> enabledCapabilities,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime closedAt
) {
}
