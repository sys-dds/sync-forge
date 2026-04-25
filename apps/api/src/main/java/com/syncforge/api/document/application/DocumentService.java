package com.syncforge.api.document.application;

import java.util.UUID;

import com.syncforge.api.document.api.CreateDocumentRequest;
import com.syncforge.api.document.model.Document;
import com.syncforge.api.document.store.DocumentRepository;
import com.syncforge.api.shared.BadRequestException;
import com.syncforge.api.shared.ConflictException;
import com.syncforge.api.shared.NotFoundException;
import com.syncforge.api.shared.RequestValidator;
import com.syncforge.api.workspace.store.WorkspaceRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class DocumentService {
    private final DocumentRepository documentRepository;
    private final WorkspaceRepository workspaceRepository;

    public DocumentService(DocumentRepository documentRepository, WorkspaceRepository workspaceRepository) {
        this.documentRepository = documentRepository;
        this.workspaceRepository = workspaceRepository;
    }

    public Document create(UUID workspaceId, CreateDocumentRequest request) {
        if (request == null) {
            throw new BadRequestException("INVALID_REQUEST", "Request body is required");
        }
        if (!workspaceRepository.existsById(workspaceId)) {
            throw new NotFoundException("WORKSPACE_NOT_FOUND", "Workspace not found");
        }
        String documentKey = RequestValidator.requiredText(request.documentKey(), "documentKey");
        String title = RequestValidator.requiredText(request.title(), "title");
        try {
            return documentRepository.create(workspaceId, documentKey, title);
        } catch (DuplicateKeyException exception) {
            throw new ConflictException("DOCUMENT_ALREADY_EXISTS", "Document key already exists in workspace");
        }
    }

    public Document get(UUID documentId) {
        return documentRepository.findById(documentId)
                .orElseThrow(() -> new NotFoundException("DOCUMENT_NOT_FOUND", "Document not found"));
    }
}
