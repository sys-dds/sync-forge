package com.syncforge.api.documentstate.api;

import java.util.UUID;

import com.syncforge.api.documentstate.application.DocumentStateService;
import com.syncforge.api.shared.RequestValidator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rooms/{roomId}/document-state")
public class DocumentStateController {
    private final DocumentStateService documentStateService;

    public DocumentStateController(DocumentStateService documentStateService) {
        this.documentStateService = documentStateService;
    }

    @GetMapping
    public DocumentStateResponse getDocumentState(@PathVariable String roomId) {
        UUID parsedRoomId = RequestValidator.parseUuid(roomId, "roomId");
        return DocumentStateResponse.from(documentStateService.getOrInitialize(parsedRoomId));
    }

    @PostMapping("/rebuild")
    public RebuildDocumentStateResponse rebuild(@PathVariable String roomId) {
        UUID parsedRoomId = RequestValidator.parseUuid(roomId, "roomId");
        DocumentStateService.RebuildResult result = documentStateService.rebuildFromOperationLog(parsedRoomId);
        return RebuildDocumentStateResponse.from(result.state(), result.operationsReplayed(), result.replayEquivalent());
    }
}
