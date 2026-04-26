package com.syncforge.api.node;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class NodeIdentity {
    private final String nodeId;
    private final long heartbeatTtlSeconds;

    public NodeIdentity(
            @Value("${syncforge.node.node-id:local-node-1}") String nodeId,
            @Value("${syncforge.node.heartbeat-ttl-seconds:30}") long heartbeatTtlSeconds) {
        this.nodeId = nodeId;
        this.heartbeatTtlSeconds = heartbeatTtlSeconds;
    }

    public String nodeId() {
        return nodeId;
    }

    public long heartbeatTtlSeconds() {
        return heartbeatTtlSeconds;
    }
}
