package com.syncforge.api.resume.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.syncforge.api.documentstate.application.DocumentStateService;
import com.syncforge.api.documentstate.model.DocumentLiveState;
import com.syncforge.api.operation.model.OperationRecord;
import com.syncforge.api.operation.store.OperationRepository;
import com.syncforge.api.resume.model.ResumeDecision;
import com.syncforge.api.resume.model.ResumeDecisionType;
import com.syncforge.api.resume.model.ResumeWindow;
import com.syncforge.api.resume.model.SnapshotRefresh;
import com.syncforge.api.room.application.RoomPermissionService;
import com.syncforge.api.shared.BadRequestException;
import com.syncforge.api.snapshot.model.DocumentSnapshot;
import com.syncforge.api.snapshot.store.SnapshotRepository;
import org.springframework.stereotype.Service;

@Service
public class ResumeWindowService {
    private static final String BEHIND_MINIMUM = "CLIENT_BEHIND_MINIMUM_RESUMABLE_SEQUENCE";

    private final DocumentStateService documentStateService;
    private final SnapshotRepository snapshotRepository;
    private final OperationRepository operationRepository;
    private final RoomPermissionService permissionService;

    public ResumeWindowService(
            DocumentStateService documentStateService,
            SnapshotRepository snapshotRepository,
            OperationRepository operationRepository,
            RoomPermissionService permissionService) {
        this.documentStateService = documentStateService;
        this.snapshotRepository = snapshotRepository;
        this.operationRepository = operationRepository;
        this.permissionService = permissionService;
    }

    public ResumeWindow window(UUID roomId) {
        DocumentLiveState state = documentStateService.getOrInitialize(roomId);
        long snapshotRoomSeq = snapshotRepository.findLatest(roomId)
                .map(DocumentSnapshot::roomSeq)
                .orElse(0L);
        return new ResumeWindow(roomId, snapshotRoomSeq, snapshotRoomSeq, state.currentRoomSeq());
    }

    public ResumeDecision decide(UUID roomId, UUID userId, long fromRoomSeq) {
        permissionService.requireView(roomId, userId);
        if (fromRoomSeq < 0) {
            throw new BadRequestException("INVALID_RESUME_RANGE", "fromRoomSeq must be non-negative");
        }
        ResumeWindow window = window(roomId);
        if (fromRoomSeq > window.latestRoomSeq()) {
            throw new BadRequestException("INVALID_RESUME_RANGE", "fromRoomSeq is ahead of latest roomSeq");
        }
        if (fromRoomSeq < window.minimumResumableRoomSeq()) {
            return new ResumeDecision(
                    ResumeDecisionType.REFRESH_REQUIRED,
                    roomId,
                    fromRoomSeq,
                    fromRoomSeq,
                    window.latestRoomSeq(),
                    window.minimumResumableRoomSeq(),
                    window.snapshotRoomSeq(),
                    window.latestRoomSeq(),
                    BEHIND_MINIMUM,
                    List.of());
        }
        List<OperationRecord> tail = operationRepository.findActiveByRoomAfterRoomSeq(roomId, fromRoomSeq);
        long toRoomSeq = tail.stream().mapToLong(OperationRecord::roomSeq).max().orElse(window.latestRoomSeq());
        return new ResumeDecision(
                ResumeDecisionType.RESUMABLE,
                roomId,
                fromRoomSeq,
                fromRoomSeq,
                toRoomSeq,
                window.minimumResumableRoomSeq(),
                window.snapshotRoomSeq(),
                window.latestRoomSeq(),
                null,
                tail.stream().map(this::eventPayload).toList());
    }

    public SnapshotRefresh snapshotRefresh(UUID roomId, UUID userId) {
        permissionService.requireView(roomId, userId);
        DocumentLiveState state = documentStateService.getOrInitialize(roomId);
        DocumentSnapshot snapshot = snapshotRepository.findLatest(roomId).orElse(null);
        long snapshotRoomSeq = snapshot == null ? 0 : snapshot.roomSeq();
        UUID snapshotId = snapshot == null ? null : snapshot.id();
        return new SnapshotRefresh(
                roomId,
                snapshotId,
                snapshotRoomSeq,
                snapshotRoomSeq,
                state.currentRoomSeq(),
                state.contentText(),
                state.contentChecksum());
    }

    private Map<String, Object> eventPayload(OperationRecord operation) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("operationId", operation.operationId());
        payload.put("userId", operation.userId().toString());
        payload.put("clientSeq", operation.clientSeq());
        payload.put("roomSeq", operation.roomSeq());
        payload.put("revision", operation.resultingRevision());
        payload.put("operationType", operation.operationType());
        payload.put("operation", operation.operation());
        return payload;
    }
}
