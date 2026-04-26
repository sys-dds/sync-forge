package com.syncforge.api.documentstate.api;

import java.util.UUID;

import com.syncforge.api.documentstate.application.DocumentStateService;
import com.syncforge.api.room.application.RoomPermissionService;
import com.syncforge.api.shared.RequestValidator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rooms/{roomId}/document-state")
public class DocumentStateController {
    private final DocumentStateService documentStateService;
    private final RoomPermissionService permissionService;

    public DocumentStateController(DocumentStateService documentStateService, RoomPermissionService permissionService) {
        this.documentStateService = documentStateService;
        this.permissionService = permissionService;
    }

    @GetMapping
    public DocumentStateResponse getDocumentState(@PathVariable String roomId, @RequestParam(required = false) String userId) {
        UUID parsedRoomId = RequestValidator.parseUuid(roomId, "roomId");
        requireViewIfPresent(parsedRoomId, userId);
        return DocumentStateResponse.from(documentStateService.getOrInitialize(parsedRoomId));
    }

    @PostMapping("/rebuild")
    public RebuildDocumentStateResponse rebuild(@PathVariable String roomId, @RequestParam(required = false) String userId) {
        UUID parsedRoomId = RequestValidator.parseUuid(roomId, "roomId");
        requireViewIfPresent(parsedRoomId, userId);
        DocumentStateService.RebuildResult result = documentStateService.rebuildFromOperationLog(parsedRoomId);
        return RebuildDocumentStateResponse.from(result.state(), result.operationsReplayed(), result.replayEquivalent());
    }

    private void requireViewIfPresent(UUID roomId, String userId) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        permissionService.requireView(roomId, RequestValidator.parseUuid(userId, "userId"));
    }
}
