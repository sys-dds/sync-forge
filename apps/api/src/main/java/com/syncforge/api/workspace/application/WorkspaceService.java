package com.syncforge.api.workspace.application;

import java.util.UUID;

import com.syncforge.api.shared.BadRequestException;
import com.syncforge.api.shared.ConflictException;
import com.syncforge.api.shared.NotFoundException;
import com.syncforge.api.shared.RequestValidator;
import com.syncforge.api.workspace.api.CreateWorkspaceRequest;
import com.syncforge.api.workspace.model.Workspace;
import com.syncforge.api.workspace.store.WorkspaceRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class WorkspaceService {
    private final WorkspaceRepository workspaceRepository;

    public WorkspaceService(WorkspaceRepository workspaceRepository) {
        this.workspaceRepository = workspaceRepository;
    }

    public Workspace create(CreateWorkspaceRequest request) {
        if (request == null) {
            throw new BadRequestException("INVALID_REQUEST", "Request body is required");
        }
        String workspaceKey = RequestValidator.requiredText(request.workspaceKey(), "workspaceKey");
        String name = RequestValidator.requiredText(request.name(), "name");
        try {
            return workspaceRepository.create(workspaceKey, name);
        } catch (DuplicateKeyException exception) {
            throw new ConflictException("WORKSPACE_ALREADY_EXISTS", "Workspace key already exists");
        }
    }

    public Workspace get(UUID workspaceId) {
        return workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new NotFoundException("WORKSPACE_NOT_FOUND", "Workspace not found"));
    }
}
