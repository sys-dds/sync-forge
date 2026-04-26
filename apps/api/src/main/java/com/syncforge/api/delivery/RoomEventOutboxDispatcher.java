package com.syncforge.api.delivery;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

import com.syncforge.api.node.NodeIdentity;
import com.syncforge.api.stream.application.RoomEventStreamProperties;
import com.syncforge.api.stream.application.RoomEventStreamPublisher;
import com.syncforge.api.stream.application.StreamPublishException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class RoomEventOutboxDispatcher {
    private final RoomEventOutboxRepository outboxRepository;
    private final RoomEventStreamPublisher streamPublisher;
    private final RoomEventStreamProperties streamProperties;
    private final NodeIdentity nodeIdentity;
    private final Duration lockTtl;

    public RoomEventOutboxDispatcher(
            RoomEventOutboxRepository outboxRepository,
            RoomEventStreamPublisher streamPublisher,
            RoomEventStreamProperties streamProperties,
            NodeIdentity nodeIdentity,
            @Value("${syncforge.delivery.outbox.lock-ttl-ms:30000}") long lockTtlMs) {
        this.outboxRepository = outboxRepository;
        this.streamPublisher = streamPublisher;
        this.streamProperties = streamProperties;
        this.nodeIdentity = nodeIdentity;
        this.lockTtl = Duration.ofMillis(lockTtlMs);
    }

    public int dispatchOnce(int limit) {
        if (limit <= 0 || !streamProperties.enabled()) {
            return 0;
        }
        outboxRepository.releaseExpiredLocks();
        List<RoomEventOutboxRecord> claimed = outboxRepository.findDueForDispatch(limit, nodeIdentity.nodeId(), lockTtl);
        int published = 0;
        for (RoomEventOutboxRecord record : claimed) {
            if (publishOne(record)) {
                published++;
            }
        }
        return published;
    }

    private boolean publishOne(RoomEventOutboxRecord record) {
        try {
            PublishedRoomEvent published = streamPublisher.publishOutboxEvent(record)
                    .orElseThrow(() -> new StreamPublishException("Redis Streams are disabled for outbox dispatch", null));
            outboxRepository.markPublished(record.id(), published.streamKey(), published.streamId());
            return true;
        } catch (RuntimeException exception) {
            int nextAttempt = record.attemptCount() + 1;
            String error = rootMessage(exception);
            if (nextAttempt >= record.maxAttempts()) {
                outboxRepository.markParked(record.id(), error);
                return false;
            }
            outboxRepository.markRetry(record.id(), error, OffsetDateTime.now().plusSeconds(backoffSeconds(nextAttempt)));
            return false;
        }
    }

    private long backoffSeconds(int attemptCount) {
        return Math.min(60, attemptCount * 2L);
    }

    private String rootMessage(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        return cursor.getMessage() == null ? throwable.getMessage() : cursor.getMessage();
    }
}
