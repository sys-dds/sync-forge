package com.syncforge.api.room.application;

import java.util.UUID;

import com.syncforge.api.document.model.Document;
import com.syncforge.api.document.store.DocumentRepository;
import com.syncforge.api.room.api.CreateRoomRequest;
import com.syncforge.api.room.model.Room;
import com.syncforge.api.room.store.RoomRepository;
import com.syncforge.api.shared.BadRequestException;
import com.syncforge.api.shared.ConflictException;
import com.syncforge.api.shared.NotFoundException;
import com.syncforge.api.shared.RequestValidator;
import com.syncforge.api.workspace.store.WorkspaceRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class RoomService {
    private final RoomRepository roomRepository;
    private final WorkspaceRepository workspaceRepository;
    private final DocumentRepository documentRepository;

    public RoomService(RoomRepository roomRepository, WorkspaceRepository workspaceRepository, DocumentRepository documentRepository) {
        this.roomRepository = roomRepository;
        this.workspaceRepository = workspaceRepository;
        this.documentRepository = documentRepository;
    }

    public Room create(UUID workspaceId, UUID documentId, CreateRoomRequest request) {
        if (request == null) {
            throw new BadRequestException("INVALID_REQUEST", "Request body is required");
        }
        if (!workspaceRepository.existsById(workspaceId)) {
            throw new NotFoundException("WORKSPACE_NOT_FOUND", "Workspace not found");
        }
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new NotFoundException("DOCUMENT_NOT_FOUND", "Document not found"));
        if (!document.workspaceId().equals(workspaceId)) {
            throw new BadRequestException("INVALID_DOCUMENT_WORKSPACE", "Document does not belong to workspace");
        }
        String roomKey = RequestValidator.requiredText(request.roomKey(), "roomKey");
        String roomType = RequestValidator.requiredText(request.roomType(), "roomType");
        if (!"DOCUMENT".equals(roomType)) {
            throw new BadRequestException("INVALID_ROOM_TYPE", "roomType must be DOCUMENT");
        }
        try {
            return roomRepository.create(workspaceId, documentId, roomKey, roomType);
        } catch (DuplicateKeyException exception) {
            throw new ConflictException("ROOM_ALREADY_EXISTS", "Room key already exists in workspace");
        }
    }

    public Room get(UUID roomId) {
        return roomRepository.findById(roomId)
                .orElseThrow(() -> new NotFoundException("ROOM_NOT_FOUND", "Room not found"));
    }
}
