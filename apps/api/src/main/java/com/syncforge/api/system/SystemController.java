package com.syncforge.api.system;

import com.syncforge.api.node.NodeHeartbeatService;
import com.syncforge.api.node.NodeStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
public class SystemController {
    private final NodeHeartbeatService nodeHeartbeatService;

    public SystemController(NodeHeartbeatService nodeHeartbeatService) {
        this.nodeHeartbeatService = nodeHeartbeatService;
    }

    @GetMapping("/ping")
    public PingResponse ping() {
        return new PingResponse("sync-forge-api", "ok");
    }

    @GetMapping("/node")
    public NodeStatus node() {
        return nodeHeartbeatService.heartbeat();
    }

    public record PingResponse(String service, String status) {
    }
}
