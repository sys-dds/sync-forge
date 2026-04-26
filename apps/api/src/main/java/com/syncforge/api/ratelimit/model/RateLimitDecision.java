package com.syncforge.api.ratelimit.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public record RateLimitDecision(
        boolean allowed,
        UUID roomId,
        UUID userId,
        String connectionId,
        String clientSessionId,
        String operationId,
        String limitKey,
        int limitValue,
        int observedValue,
        int windowSeconds,
        String decision,
        String reason,
        long retryAfterMs,
        OffsetDateTime createdAt) {
    public static RateLimitDecision allowed(
            UUID roomId,
            UUID userId,
            String connectionId,
            String clientSessionId,
            String operationId,
            String limitKey,
            int limitValue,
            int observedValue,
            int windowSeconds) {
        return new RateLimitDecision(true, roomId, userId, connectionId, clientSessionId, operationId, limitKey,
                limitValue, observedValue, windowSeconds, "ALLOWED", "within configured operation rate limit", 0,
                OffsetDateTime.now());
    }

    public static RateLimitDecision rejected(
            UUID roomId,
            UUID userId,
            String connectionId,
            String clientSessionId,
            String operationId,
            String limitKey,
            int limitValue,
            int observedValue,
            int windowSeconds,
            long retryAfterMs) {
        return new RateLimitDecision(false, roomId, userId, connectionId, clientSessionId, operationId, limitKey,
                limitValue, observedValue, windowSeconds, "REJECTED", "operation rate limit exceeded", retryAfterMs,
                OffsetDateTime.now());
    }
}
