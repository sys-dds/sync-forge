package com.syncforge.api.presence.api;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.syncforge.api.presence.application.PresenceService;
import com.syncforge.api.shared.RequestValidator;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rooms/{roomId}/presence")
public class PresenceController {
    private final PresenceService presenceService;

    public PresenceController(PresenceService presenceService) {
        this.presenceService = presenceService;
    }

    @GetMapping
    public List<UserPresenceResponse> roomPresence(
            @PathVariable String roomId,
            @RequestHeader(value = "X-User-Id", required = false) String requesterUserId) {
        UUID parsedRoomId = RequestValidator.parseUuid(roomId, "roomId");
        return presenceService.findRoomPresence(parsedRoomId, requesterUserId).stream()
                .map(UserPresenceResponse::from)
                .toList();
    }

    @GetMapping("/connections")
    public List<PresenceConnectionResponse> roomPresenceConnections(
            @PathVariable String roomId,
            @RequestHeader(value = "X-User-Id", required = false) String requesterUserId) {
        UUID parsedRoomId = RequestValidator.parseUuid(roomId, "roomId");
        return presenceService.findRoomPresenceConnections(parsedRoomId, requesterUserId).stream()
                .map(PresenceConnectionResponse::from)
                .toList();
    }

    @PostMapping("/expire")
    public ExpirePresenceResponse expirePresence(
            @PathVariable String roomId,
            @RequestBody(required = false) ExpirePresenceRequest request,
            @RequestHeader(value = "X-User-Id", required = false) String requesterUserId) {
        UUID parsedRoomId = RequestValidator.parseUuid(roomId, "roomId");
        presenceService.findRoomPresence(parsedRoomId, requesterUserId);
        OffsetDateTime now = request == null ? null : request.now();
        return new ExpirePresenceResponse(presenceService.expireStalePresence(now));
    }
}
