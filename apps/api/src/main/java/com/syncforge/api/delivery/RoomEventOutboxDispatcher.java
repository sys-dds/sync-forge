package com.syncforge.api.delivery;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

import com.syncforge.api.node.NodeIdentity;
import com.syncforge.api.ownership.RoomOwnershipLease;
import com.syncforge.api.ownership.RoomOwnershipService;
import com.syncforge.api.stream.application.RoomEventStreamProperties;
import com.syncforge.api.stream.application.RoomEventStreamPublisher;
import com.syncforge.api.stream.application.StreamPublishException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class RoomEventOutboxDispatcher {
    private final RoomEventOutboxRepository outboxRepository;
    private final RoomEventStreamPublisher streamPublisher;
    private final RoomEventStreamProperties streamProperties;
    private final NodeIdentity nodeIdentity;
    private final RoomOwnershipService ownershipService;
    private final Duration lockTtl;

    @Autowired
    public RoomEventOutboxDispatcher(
            RoomEventOutboxRepository outboxRepository,
            RoomEventStreamPublisher streamPublisher,
            RoomEventStreamProperties streamProperties,
            NodeIdentity nodeIdentity,
            RoomOwnershipService ownershipService,
            @Value("${syncforge.delivery.outbox.lock-ttl-ms:30000}") long lockTtlMs) {
        this.outboxRepository = outboxRepository;
        this.streamPublisher = streamPublisher;
        this.streamProperties = streamProperties;
        this.nodeIdentity = nodeIdentity;
        this.ownershipService = ownershipService;
        this.lockTtl = Duration.ofMillis(lockTtlMs);
    }

    public RoomEventOutboxDispatcher(
            RoomEventOutboxRepository outboxRepository,
            RoomEventStreamPublisher streamPublisher,
            RoomEventStreamProperties streamProperties,
            NodeIdentity nodeIdentity,
            long lockTtlMs) {
        this(outboxRepository, streamPublisher, streamProperties, nodeIdentity, null, lockTtlMs);
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

    public int dispatchRoomOnce(java.util.UUID roomId, int limit) {
        if (limit <= 0 || !streamProperties.enabled()) {
            return 0;
        }
        outboxRepository.releaseExpiredLocks();
        List<RoomEventOutboxRecord> claimed = outboxRepository.findDueForDispatch(roomId, limit, nodeIdentity.nodeId(), lockTtl);
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
            RoomOwnershipLease lease = ownershipService == null ? null : ownershipService.currentOwnership(record.roomId());
            if (ownershipService != null && lease == null) {
                lease = ownershipService.acquireOrRenew(record.roomId(), nodeIdentity.nodeId());
            }
            if (ownershipService != null && !nodeIdentity.nodeId().equals(lease.ownerNodeId())) {
                ownershipService.recordFencedPublishRejected(record.roomId(), nodeIdentity.nodeId(), record.fencingToken());
                outboxRepository.markRetry(record.id(), "FENCING_TOKEN_REJECTED",
                        OffsetDateTime.now().plusSeconds(backoffSeconds(record.attemptCount() + 1)));
                return false;
            }
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
