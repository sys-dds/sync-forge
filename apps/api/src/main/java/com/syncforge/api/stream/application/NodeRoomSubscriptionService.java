package com.syncforge.api.stream.application;

import java.util.List;
import java.util.UUID;

import com.syncforge.api.node.NodeIdentity;
import com.syncforge.api.stream.model.NodeRoomSubscription;
import com.syncforge.api.stream.store.NodeRoomSubscriptionRepository;
import org.springframework.stereotype.Service;

@Service
public class NodeRoomSubscriptionService {
    private final NodeRoomSubscriptionRepository repository;
    private final NodeIdentity nodeIdentity;

    public NodeRoomSubscriptionService(NodeRoomSubscriptionRepository repository, NodeIdentity nodeIdentity) {
        this.repository = repository;
        this.nodeIdentity = nodeIdentity;
    }

    public void joined(UUID roomId) {
        repository.increment(nodeIdentity.nodeId(), roomId);
    }

    public void left(UUID roomId) {
        repository.decrement(nodeIdentity.nodeId(), roomId);
    }

    public void markEvent(UUID roomId) {
        repository.markEvent(nodeIdentity.nodeId(), roomId);
    }

    public List<NodeRoomSubscription> activeSubscriptions() {
        return repository.activeSubscriptions(nodeIdentity.nodeId());
    }
}
