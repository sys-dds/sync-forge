package com.syncforge.api.node;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class CrossNodePresenceService {
    private final CrossNodePresenceRepository repository;
    private final NodeIdentity nodeIdentity;
    private final long nodeHeartbeatTtlSeconds;

    public CrossNodePresenceService(
            CrossNodePresenceRepository repository,
            NodeIdentity nodeIdentity,
            @Value("${syncforge.node.heartbeat-ttl-seconds:30}") long nodeHeartbeatTtlSeconds) {
        this.repository = repository;
        this.nodeIdentity = nodeIdentity;
        this.nodeHeartbeatTtlSeconds = nodeHeartbeatTtlSeconds;
    }

    public void updateLocal(UUID roomId, UUID userId) {
        repository.updateLocal(roomId, userId, nodeIdentity.nodeId(), OffsetDateTime.now());
    }

    public int markStaleNodePresence(OffsetDateTime now) {
        OffsetDateTime effectiveNow = now == null ? OffsetDateTime.now() : now;
        return repository.markStale(effectiveNow.minusSeconds(nodeHeartbeatTtlSeconds));
    }

    public List<CrossNodePresenceState> listByRoom(UUID roomId) {
        markStaleNodePresence(null);
        return repository.listByRoom(roomId);
    }
}
