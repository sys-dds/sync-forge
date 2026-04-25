package com.syncforge.api.workspace.api;

import java.util.UUID;

import com.syncforge.api.shared.RequestValidator;
import com.syncforge.api.workspace.application.WorkspaceService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workspaces")
public class WorkspaceController {
    private final WorkspaceService workspaceService;

    public WorkspaceController(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WorkspaceResponse create(@RequestBody CreateWorkspaceRequest request) {
        return WorkspaceResponse.from(workspaceService.create(request));
    }

    @GetMapping("/{workspaceId}")
    public WorkspaceResponse get(@PathVariable String workspaceId) {
        UUID id = RequestValidator.parseUuid(workspaceId, "workspaceId");
        return WorkspaceResponse.from(workspaceService.get(id));
    }
}
