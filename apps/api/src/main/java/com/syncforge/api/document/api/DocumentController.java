package com.syncforge.api.document.api;

import java.util.UUID;

import com.syncforge.api.document.application.DocumentService;
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
public class DocumentController {
    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping("/workspaces/{workspaceId}/documents")
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentResponse create(@PathVariable String workspaceId, @RequestBody CreateDocumentRequest request) {
        UUID parsedWorkspaceId = RequestValidator.parseUuid(workspaceId, "workspaceId");
        return DocumentResponse.from(documentService.create(parsedWorkspaceId, request));
    }

    @GetMapping("/documents/{documentId}")
    public DocumentResponse get(@PathVariable String documentId) {
        UUID parsedDocumentId = RequestValidator.parseUuid(documentId, "documentId");
        return DocumentResponse.from(documentService.get(parsedDocumentId));
    }
}
