package com.syncforge.api.ownership;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import com.syncforge.api.shared.ConflictException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoomOwnershipService {
    private final RoomOwnershipRepository repository;
    private final Clock clock;
    private final long leaseTtlSeconds;

    public RoomOwnershipService(
            RoomOwnershipRepository repository,
            @Value("${syncforge.ownership.lease-ttl-seconds:30}") long leaseTtlSeconds) {
        this.repository = repository;
        this.clock = Clock.systemUTC();
        this.leaseTtlSeconds = leaseTtlSeconds;
    }

    @Transactional(noRollbackFor = ConflictException.class)
    public RoomOwnershipLease acquireOrRenew(UUID roomId, String nodeId) {
        return acquireOrRenew(roomId, nodeId, now());
    }

    @Transactional(noRollbackFor = ConflictException.class)
    public RoomOwnershipLease acquireOrRenew(UUID roomId, String nodeId, OffsetDateTime now) {
        Optional<RoomOwnershipLease> existing = repository.lock(roomId);
        OffsetDateTime expiresAt = now.plusSeconds(leaseTtlSeconds);
        if (existing.isEmpty()) {
            Optional<RoomOwnershipLease> acquired = repository.insertFirst(roomId, nodeId, expiresAt);
            if (acquired.isPresent()) {
                repository.recordEvent(roomId, nodeId, acquired.get().fencingToken(), "ACQUIRED", "FIRST_OWNER", null, null);
                return acquired.get();
            }
            existing = repository.lock(roomId);
        }
        RoomOwnershipLease lease = existing.get();
        if (lease.leaseStatus() == RoomOwnershipStatus.ACTIVE && lease.leaseExpiresAt().isAfter(now)) {
            if (!lease.ownerNodeId().equals(nodeId)) {
                repository.recordEvent(roomId, nodeId, lease.fencingToken(), "STALE_OWNER_REJECTED",
                        "ACTIVE_LEASE_HELD_BY_DIFFERENT_NODE", lease.ownerNodeId(), lease.fencingToken());
                throw new ConflictException("ROOM_OWNERSHIP_HELD",
                        "Room ownership lease is active for another node");
            }
            RoomOwnershipLease renewed = repository.renew(roomId, nodeId, lease.fencingToken(), expiresAt);
            repository.recordEvent(roomId, nodeId, renewed.fencingToken(), "RENEWED", "OWNER_RENEWED",
                    lease.ownerNodeId(), lease.fencingToken());
            return renewed;
        }
        RoomOwnershipLease takeover = repository.takeover(roomId, nodeId, lease.fencingToken() + 1, expiresAt,
                lease.leaseStatus() == RoomOwnershipStatus.RELEASED ? "LEASE_RELEASED" : "LEASE_EXPIRED");
        repository.recordEvent(roomId, nodeId, takeover.fencingToken(), "TAKEOVER",
                takeover.lastTakeoverReason(), lease.ownerNodeId(), lease.fencingToken());
        return takeover;
    }

    @Transactional(noRollbackFor = ConflictException.class)
    public RoomOwnershipLease takeoverExpired(UUID roomId, String nodeId, String reason) {
        return takeoverExpired(roomId, nodeId, reason, now());
    }

    @Transactional(noRollbackFor = ConflictException.class)
    public RoomOwnershipLease takeoverExpired(UUID roomId, String nodeId, String reason, OffsetDateTime now) {
        Optional<RoomOwnershipLease> existing = repository.lock(roomId);
        if (existing.isEmpty()) {
            return acquireOrRenew(roomId, nodeId, now);
        }
        RoomOwnershipLease lease = existing.get();
        if (lease.leaseStatus() == RoomOwnershipStatus.ACTIVE && lease.leaseExpiresAt().isAfter(now)) {
            if (lease.ownerNodeId().equals(nodeId)) {
                return repository.renew(roomId, nodeId, lease.fencingToken(), now.plusSeconds(leaseTtlSeconds));
            }
            repository.recordEvent(roomId, nodeId, lease.fencingToken(), "STALE_OWNER_REJECTED",
                    "LEASE_NOT_EXPIRED", lease.ownerNodeId(), lease.fencingToken());
            throw new ConflictException("ROOM_OWNERSHIP_HELD",
                    "Room ownership lease has not expired");
        }
        RoomOwnershipLease takeover = repository.takeover(roomId, nodeId, lease.fencingToken() + 1,
                now.plusSeconds(leaseTtlSeconds), reason);
        repository.recordEvent(roomId, nodeId, takeover.fencingToken(), "TAKEOVER", reason,
                lease.ownerNodeId(), lease.fencingToken());
        return takeover;
    }

    public RoomOwnershipDecision ensureCurrentOwner(UUID roomId, String nodeId, long fencingToken) {
        RoomOwnershipLease lease = repository.find(roomId)
                .orElse(null);
        OffsetDateTime now = now();
        if (lease == null
                || lease.leaseStatus() != RoomOwnershipStatus.ACTIVE
                || !lease.leaseExpiresAt().isAfter(now)
                || !lease.ownerNodeId().equals(nodeId)
                || lease.fencingToken() != fencingToken) {
            recordStaleOwnerRejected(roomId, nodeId, fencingToken, "FENCING_TOKEN_REJECTED");
            return RoomOwnershipDecision.rejected("FENCING_TOKEN_REJECTED",
                    "Room owner fencing token is not current", lease);
        }
        return RoomOwnershipDecision.accepted(lease);
    }

    public RoomOwnershipLease currentOwnership(UUID roomId) {
        return repository.find(roomId).orElse(null);
    }

    @Transactional
    public RoomOwnershipLease release(UUID roomId, String nodeId, long fencingToken, String reason) {
        RoomOwnershipLease released = repository.release(roomId, nodeId, fencingToken, reason)
                .orElseThrow(() -> new ConflictException("FENCING_TOKEN_REJECTED",
                        "Only the current owner can release ownership"));
        repository.recordEvent(roomId, nodeId, fencingToken, "RELEASED", reason, nodeId, fencingToken);
        return released;
    }

    @Transactional
    public int markExpiredLeases(OffsetDateTime now) {
        int expired = repository.markExpired(now);
        return expired;
    }

    public void recordStaleOwnerRejected(UUID roomId, String nodeId, long fencingToken, String reason) {
        RoomOwnershipLease current = repository.find(roomId).orElse(null);
        repository.recordEvent(roomId, nodeId, fencingToken, "STALE_OWNER_REJECTED", reason,
                current == null ? null : current.ownerNodeId(),
                current == null ? null : current.fencingToken());
    }

    public void recordFencedWriteRejected(UUID roomId, String nodeId, long fencingToken) {
        repository.recordEvent(roomId, nodeId, fencingToken, "FENCED_WRITE_REJECTED",
                "STALE_OWNER_WRITE_REJECTED", null, null);
    }

    public void recordFencedPublishRejected(UUID roomId, String nodeId, Long fencingToken) {
        RoomOwnershipLease current = repository.find(roomId).orElse(null);
        repository.recordEvent(roomId, nodeId, fencingToken, "FENCED_PUBLISH_REJECTED",
                "STALE_OWNER_PUBLISH_REJECTED",
                current == null ? null : current.ownerNodeId(),
                current == null ? null : current.fencingToken());
    }

    public Optional<RoomOwnershipEvent> latestEvent(UUID roomId) {
        return repository.latestEvent(roomId);
    }

    OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }
}
