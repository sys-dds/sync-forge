package com.syncforge.api.backpressure.application;

import java.util.List;
import java.util.UUID;

import com.syncforge.api.backpressure.model.FlowStatus;
import com.syncforge.api.backpressure.store.ConnectionFlowControlRepository;
import com.syncforge.api.node.NodeIdentity;
import com.syncforge.api.shared.NotFoundException;
import com.syncforge.api.websocket.JoinedRoomConnection;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ConnectionFlowControlService {
    private final ConnectionFlowControlRepository repository;
    private final NodeIdentity nodeIdentity;
    private final int maxOutboundQueueSize;
    private final int slowConsumerQueuedMessages;

    public ConnectionFlowControlService(
            ConnectionFlowControlRepository repository,
            NodeIdentity nodeIdentity,
            @Value("${syncforge.websocket.max-outbound-queue-size:100}") int maxOutboundQueueSize,
            @Value("${syncforge.websocket.slow-consumer-queued-messages:80}") int slowConsumerQueuedMessages) {
        this.repository = repository;
        this.nodeIdentity = nodeIdentity;
        this.maxOutboundQueueSize = maxOutboundQueueSize;
        this.slowConsumerQueuedMessages = slowConsumerQueuedMessages;
    }

    public void register(JoinedRoomConnection connection) {
        repository.createActive(connection.connectionId(), connection.roomId(), connection.userId(),
                connection.websocketSessionId(), nodeIdentity.nodeId(), maxOutboundQueueSize);
    }

    public void updateQueued(String connectionId, int queuedMessages) {
        repository.updateQueued(connectionId, queuedMessages);
        if (queuedMessages >= slowConsumerQueuedMessages) {
            repository.markSlow(connectionId, queuedMessages);
        }
    }

    public void markSendStarted(String connectionId) {
        repository.markSendStarted(connectionId);
    }

    public void markSendCompleted(String connectionId) {
        repository.markSendCompleted(connectionId);
    }

    public void recordSendFailure(String connectionId, String error) {
        repository.recordSendFailure(connectionId, error);
    }

    public void markClosed(String connectionId) {
        repository.markClosed(connectionId);
    }

    public void markQuarantined(String connectionId) {
        repository.markQuarantined(connectionId);
    }

    public void markActive(String connectionId) {
        repository.markActive(connectionId);
    }

    public boolean isQuarantined(String connectionId) {
        return repository.find(connectionId)
                .map(status -> "QUARANTINED".equals(status.status()))
                .orElse(false);
    }

    public List<FlowStatus> listByRoom(UUID roomId) {
        return repository.listByRoom(roomId);
    }

    public FlowStatus require(String connectionId) {
        return repository.find(connectionId)
                .orElseThrow(() -> new NotFoundException("FLOW_STATUS_NOT_FOUND", "Connection flow status not found"));
    }

    public int maxOutboundQueueSize() {
        return maxOutboundQueueSize;
    }

    public int slowConsumerQueuedMessages() {
        return slowConsumerQueuedMessages;
    }
}
