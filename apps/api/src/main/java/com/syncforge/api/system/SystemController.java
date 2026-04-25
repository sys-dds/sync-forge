package com.syncforge.api.system;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
public class SystemController {

    @GetMapping("/ping")
    public PingResponse ping() {
        return new PingResponse("sync-forge-api", "ok");
    }

    public record PingResponse(String service, String status) {
    }
}
