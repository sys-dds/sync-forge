package com.syncforge.api.awareness.api;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.syncforge.api.awareness.application.AwarenessService;
import com.syncforge.api.shared.RequestValidator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rooms/{roomId}/awareness")
public class AwarenessController {
    private final AwarenessService awarenessService;

    public AwarenessController(AwarenessService awarenessService) {
        this.awarenessService = awarenessService;
    }

    @GetMapping
    public List<AwarenessStateResponse> roomAwareness(
            @PathVariable String roomId,
            @RequestHeader(value = "X-User-Id", required = false) String requesterUserId) {
        UUID parsedRoomId = RequestValidator.parseUuid(roomId, "roomId");
        return awarenessService.findRoomAwareness(parsedRoomId, requesterUserId).stream()
                .map(AwarenessStateResponse::from)
                .toList();
    }

    @PostMapping("/expire")
    public ExpireAwarenessResponse expireAwareness(
            @PathVariable String roomId,
            @RequestBody(required = false) ExpireAwarenessRequest request,
            @RequestHeader(value = "X-User-Id", required = false) String requesterUserId) {
        UUID parsedRoomId = RequestValidator.parseUuid(roomId, "roomId");
        awarenessService.findRoomAwareness(parsedRoomId, requesterUserId);
        OffsetDateTime now = request == null ? null : request.now();
        return new ExpireAwarenessResponse(awarenessService.expireStaleAwareness(now));
    }
}
