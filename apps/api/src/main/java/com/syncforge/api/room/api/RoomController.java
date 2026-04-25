package com.syncforge.api.room.api;

import java.util.UUID;

import com.syncforge.api.room.application.RoomService;
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
@RequestMapping("/api/v1")
public class RoomController {
    private final RoomService roomService;

    public RoomController(RoomService roomService) {
        this.roomService = roomService;
    }

    @PostMapping("/workspaces/{workspaceId}/documents/{documentId}/rooms")
    @ResponseStatus(HttpStatus.CREATED)
    public RoomResponse create(
            @PathVariable String workspaceId,
            @PathVariable String documentId,
            @RequestBody CreateRoomRequest request) {
        UUID parsedWorkspaceId = RequestValidator.parseUuid(workspaceId, "workspaceId");
        UUID parsedDocumentId = RequestValidator.parseUuid(documentId, "documentId");
        return RoomResponse.from(roomService.create(parsedWorkspaceId, parsedDocumentId, request));
    }

    @GetMapping("/rooms/{roomId}")
    public RoomResponse get(@PathVariable String roomId) {
        UUID parsedRoomId = RequestValidator.parseUuid(roomId, "roomId");
        return RoomResponse.from(roomService.get(parsedRoomId));
    }
}
