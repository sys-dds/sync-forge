package com.syncforge.api.runtime;

import java.util.UUID;

import com.syncforge.api.room.application.RoomPermissionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoomRuntimeControlService {
    private final RoomRuntimeControlRepository repository;
    private final RoomPermissionService permissionService;

    public RoomRuntimeControlService(RoomRuntimeControlRepository repository, RoomPermissionService permissionService) {
        this.repository = repository;
        this.permissionService = permissionService;
    }

    public RoomRuntimeControlState state(UUID roomId) {
        return repository.getOrCreate(roomId);
    }

    public boolean writesPaused(UUID roomId) {
        return state(roomId).writesPaused();
    }

    public long forceResyncGeneration(UUID roomId) {
        return state(roomId).forceResyncGeneration();
    }

    @Transactional
    public RoomRuntimeControlState pauseWrites(UUID roomId, UUID actorUserId, String reason) {
        permissionService.requireManageMembers(roomId, actorUserId);
        RoomRuntimeControlState previous = repository.getOrCreate(roomId);
        RoomRuntimeControlState current = repository.update(roomId, true, previous.forceResyncGeneration(), previous.forceResyncReason(),
                previous.repairRequired(), "PAUSE_WRITES", normalize(reason), actorUserId);
        repository.recordEvent(roomId, actorUserId, "PAUSE_WRITES", normalize(reason), previous, current);
        return current;
    }

    @Transactional
    public RoomRuntimeControlState resumeWrites(UUID roomId, UUID actorUserId, String reason) {
        permissionService.requireManageMembers(roomId, actorUserId);
        RoomRuntimeControlState previous = repository.getOrCreate(roomId);
        RoomRuntimeControlState current = repository.update(roomId, false, previous.forceResyncGeneration(), previous.forceResyncReason(),
                previous.repairRequired(), "RESUME_WRITES", normalize(reason), actorUserId);
        repository.recordEvent(roomId, actorUserId, "RESUME_WRITES", normalize(reason), previous, current);
        return current;
    }

    @Transactional
    public RoomRuntimeControlState forceResync(UUID roomId, UUID actorUserId, String reason) {
        permissionService.requireManageMembers(roomId, actorUserId);
        RoomRuntimeControlState previous = repository.getOrCreate(roomId);
        RoomRuntimeControlState current = repository.update(roomId, previous.writesPaused(),
                previous.forceResyncGeneration() + 1, normalize(reason), previous.repairRequired(),
                "FORCE_RESYNC", normalize(reason), actorUserId);
        repository.recordEvent(roomId, actorUserId, "FORCE_RESYNC", normalize(reason), previous, current);
        return current;
    }

    @Transactional
    public RoomRuntimeControlState markRepairRequired(UUID roomId, UUID actorUserId, String reason) {
        RoomRuntimeControlState previous = repository.getOrCreate(roomId);
        RoomRuntimeControlState current = repository.update(roomId, previous.writesPaused(), previous.forceResyncGeneration(),
                previous.forceResyncReason(), true, "MARK_REPAIR_REQUIRED", normalize(reason), actorUserId);
        repository.recordEvent(roomId, actorUserId, "MARK_REPAIR_REQUIRED", normalize(reason), previous, current);
        return current;
    }

    @Transactional
    public RoomRuntimeControlState clearRepairRequired(UUID roomId, UUID actorUserId, String reason) {
        RoomRuntimeControlState previous = repository.getOrCreate(roomId);
        RoomRuntimeControlState current = repository.update(roomId, previous.writesPaused(), previous.forceResyncGeneration(),
                previous.forceResyncReason(), false, "CLEAR_REPAIR_REQUIRED", normalize(reason), actorUserId);
        repository.recordEvent(roomId, actorUserId, "CLEAR_REPAIR_REQUIRED", normalize(reason), previous, current);
        return current;
    }

    @Transactional
    public void recordRebuild(UUID roomId, UUID actorUserId, String reason) {
        RoomRuntimeControlState previous = repository.getOrCreate(roomId);
        RoomRuntimeControlState current = repository.update(roomId, previous.writesPaused(), previous.forceResyncGeneration(),
                previous.forceResyncReason(), previous.repairRequired(), "REBUILD_STATE", normalize(reason), actorUserId);
        repository.recordEvent(roomId, actorUserId, "REBUILD_STATE", normalize(reason), previous, current);
    }

    private String normalize(String reason) {
        return reason == null || reason.isBlank() ? "OPERATOR_REQUESTED" : reason;
    }
}
