package com.syncforge.api.node;

import java.util.Map;

import org.springframework.stereotype.Service;

@Service
public class NodeHeartbeatService {
    private final NodeIdentity nodeIdentity;
    private final NodeHeartbeatRepository nodeHeartbeatRepository;

    public NodeHeartbeatService(NodeIdentity nodeIdentity, NodeHeartbeatRepository nodeHeartbeatRepository) {
        this.nodeIdentity = nodeIdentity;
        this.nodeHeartbeatRepository = nodeHeartbeatRepository;
    }

    public NodeStatus heartbeat() {
        NodeHeartbeatRepository.NodeHeartbeat heartbeat = nodeHeartbeatRepository.upsertActive(
                nodeIdentity.nodeId(),
                Map.of("service", "sync-forge-api"));
        return new NodeStatus(
                heartbeat.nodeId(),
                heartbeat.status(),
                heartbeat.startedAt(),
                heartbeat.lastSeenAt(),
                nodeIdentity.heartbeatTtlSeconds());
    }

    public int markStaleNodes() {
        return nodeHeartbeatRepository.markStale(nodeIdentity.heartbeatTtlSeconds());
    }
}
