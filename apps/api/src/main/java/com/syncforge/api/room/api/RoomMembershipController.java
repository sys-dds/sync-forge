package com.syncforge.api.room.api;

import java.util.List;
import java.util.UUID;

import com.syncforge.api.room.application.RoomMembershipService;
import com.syncforge.api.shared.RequestValidator;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rooms/{roomId}/memberships")
public class RoomMembershipController {
    private final RoomMembershipService membershipService;

    public RoomMembershipController(RoomMembershipService membershipService) {
        this.membershipService = membershipService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RoomMembershipResponse create(@PathVariable String roomId, @RequestBody CreateRoomMembershipRequest request) {
        UUID parsedRoomId = RequestValidator.parseUuid(roomId, "roomId");
        return RoomMembershipResponse.from(membershipService.create(parsedRoomId, request));
    }

    @GetMapping
    public List<RoomMembershipResponse> list(@PathVariable String roomId) {
        UUID parsedRoomId = RequestValidator.parseUuid(roomId, "roomId");
        return membershipService.list(parsedRoomId).stream()
                .map(RoomMembershipResponse::from)
                .toList();
    }
}
