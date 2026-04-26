package com.syncforge.api.ratelimit.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.syncforge.api.ratelimit.model.RateLimitDecision;
import com.syncforge.api.ratelimit.store.OperationRateLimitRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class OperationRateLimitService {
    private final OperationRateLimitRepository repository;
    private final int perConnectionPerSecond;
    private final int perUserPerRoomPerMinute;
    private final Clock clock;
    private final Map<String, Deque<Instant>> connectionWindows = new ConcurrentHashMap<>();
    private final Map<String, Deque<Instant>> userRoomWindows = new ConcurrentHashMap<>();

    @Autowired
    public OperationRateLimitService(
            OperationRateLimitRepository repository,
            @Value("${syncforge.rate-limit.operations-per-connection-per-second:20}") int perConnectionPerSecond,
            @Value("${syncforge.rate-limit.operations-per-user-per-room-per-minute:300}") int perUserPerRoomPerMinute) {
        this(repository, perConnectionPerSecond, perUserPerRoomPerMinute, Clock.systemUTC());
    }

    OperationRateLimitService(
            OperationRateLimitRepository repository,
            int perConnectionPerSecond,
            int perUserPerRoomPerMinute,
            Clock clock) {
        this.repository = repository;
        this.perConnectionPerSecond = perConnectionPerSecond;
        this.perUserPerRoomPerMinute = perUserPerRoomPerMinute;
        this.clock = clock;
    }

    public RateLimitDecision check(
            UUID roomId,
            UUID userId,
            String connectionId,
            String clientSessionId,
            String operationId) {
        Instant now = clock.instant();
        RateLimitDecision connectionDecision = checkWindow(
                roomId,
                userId,
                connectionId,
                clientSessionId,
                operationId,
                "connection:%s".formatted(connectionId),
                perConnectionPerSecond,
                1,
                connectionWindows.computeIfAbsent(connectionId, ignored -> new ArrayDeque<>()),
                now);
        repository.record(connectionDecision);
        if (!connectionDecision.allowed()) {
            return connectionDecision;
        }

        String userRoomKey = "room:%s:user:%s".formatted(roomId, userId);
        RateLimitDecision userDecision = checkWindow(
                roomId,
                userId,
                connectionId,
                clientSessionId,
                operationId,
                userRoomKey,
                perUserPerRoomPerMinute,
                60,
                userRoomWindows.computeIfAbsent(userRoomKey, ignored -> new ArrayDeque<>()),
                now);
        repository.record(userDecision);
        return userDecision;
    }

    public List<RateLimitDecision> listByRoom(UUID roomId) {
        return repository.listByRoom(roomId);
    }

    private RateLimitDecision checkWindow(
            UUID roomId,
            UUID userId,
            String connectionId,
            String clientSessionId,
            String operationId,
            String limitKey,
            int limitValue,
            int windowSeconds,
            Deque<Instant> window,
            Instant now) {
        synchronized (window) {
            Instant cutoff = now.minusSeconds(windowSeconds);
            while (!window.isEmpty() && !window.peekFirst().isAfter(cutoff)) {
                window.removeFirst();
            }
            int observed = window.size() + 1;
            if (observed > limitValue) {
                return RateLimitDecision.rejected(roomId, userId, connectionId, clientSessionId, operationId,
                        limitKey, limitValue, observed, windowSeconds, retryAfterMs(window, now, windowSeconds));
            }
            window.addLast(now);
            return RateLimitDecision.allowed(roomId, userId, connectionId, clientSessionId, operationId,
                    limitKey, limitValue, observed, windowSeconds);
        }
    }

    private long retryAfterMs(Deque<Instant> window, Instant now, int windowSeconds) {
        Instant oldest = window.peekFirst();
        if (oldest == null) {
            return Math.max(1, windowSeconds) * 1000L;
        }
        long millis = oldest.plusSeconds(windowSeconds).toEpochMilli() - now.toEpochMilli();
        return Math.max(1L, millis);
    }
}
