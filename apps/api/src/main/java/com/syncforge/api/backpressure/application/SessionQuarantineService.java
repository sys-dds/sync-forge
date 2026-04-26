package com.syncforge.api.backpressure.application;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.syncforge.api.backpressure.model.SessionQuarantine;
import com.syncforge.api.backpressure.store.SessionQuarantineRepository;
import com.syncforge.api.node.NodeIdentity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class SessionQuarantineService {
    private final SessionQuarantineRepository repository;
    private final ConnectionFlowControlService flowControlService;
    private final NodeIdentity nodeIdentity;
    private final int quarantineTtlSeconds;

    public SessionQuarantineService(
            SessionQuarantineRepository repository,
            ConnectionFlowControlService flowControlService,
            NodeIdentity nodeIdentity,
            @Value("${syncforge.websocket.quarantine-ttl-seconds:60}") int quarantineTtlSeconds) {
        this.repository = repository;
        this.flowControlService = flowControlService;
        this.nodeIdentity = nodeIdentity;
        this.quarantineTtlSeconds = quarantineTtlSeconds;
    }

    public SessionQuarantine quarantine(UUID roomId, UUID userId, String connectionId, String clientSessionId, String reason) {
        SessionQuarantine quarantine = repository.create(roomId, userId, connectionId, clientSessionId,
                nodeIdentity.nodeId(), reason, OffsetDateTime.now().plusSeconds(quarantineTtlSeconds));
        flowControlService.markQuarantined(connectionId);
        return quarantine;
    }

    public Optional<SessionQuarantine> active(String connectionId) {
        releaseExpired();
        return repository.findActive(connectionId, OffsetDateTime.now());
    }

    public boolean isQuarantined(String connectionId) {
        return active(connectionId).isPresent();
    }

    public int releaseExpired() {
        List<String> releasedConnectionIds = repository.releaseExpired(OffsetDateTime.now());
        releasedConnectionIds.forEach(flowControlService::markActive);
        return releasedConnectionIds.size();
    }

    public List<SessionQuarantine> listByRoom(UUID roomId) {
        releaseExpired();
        return repository.listByRoom(roomId);
    }
}
