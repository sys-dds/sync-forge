package com.syncforge.api.snapshot.application;

import java.util.UUID;

import com.syncforge.api.documentstate.application.DocumentStateService;
import com.syncforge.api.documentstate.model.DocumentLiveState;
import com.syncforge.api.shared.NotFoundException;
import com.syncforge.api.snapshot.model.DocumentSnapshot;
import com.syncforge.api.snapshot.store.SnapshotRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SnapshotService {
    private final SnapshotRepository snapshotRepository;
    private final DocumentStateService documentStateService;
    private final long operationThreshold;

    public SnapshotService(
            SnapshotRepository snapshotRepository,
            DocumentStateService documentStateService,
            @Value("${syncforge.snapshot.operation-threshold:5}") long operationThreshold) {
        this.snapshotRepository = snapshotRepository;
        this.documentStateService = documentStateService;
        this.operationThreshold = operationThreshold;
    }

    @Transactional
    public DocumentSnapshot createSnapshot(UUID roomId, String reason) {
        DocumentLiveState state = documentStateService.getOrInitialize(roomId);
        return snapshotRepository.create(roomId, state.documentId(), state.currentRoomSeq(), state.currentRevision(),
                state.contentText(), state.contentChecksum(), reason);
    }

    public DocumentSnapshot getLatestSnapshot(UUID roomId) {
        return snapshotRepository.findLatest(roomId)
                .orElseThrow(() -> new NotFoundException("SNAPSHOT_NOT_FOUND", "Snapshot not found"));
    }

    @Transactional
    public DocumentSnapshot createPeriodicSnapshotIfDue(UUID roomId) {
        DocumentLiveState state = documentStateService.getOrInitialize(roomId);
        long latestRevision = snapshotRepository.findLatest(roomId)
                .map(DocumentSnapshot::revision)
                .orElse(0L);
        if (state.currentRevision() - latestRevision < operationThreshold) {
            return null;
        }
        return snapshotRepository.create(roomId, state.documentId(), state.currentRoomSeq(), state.currentRevision(),
                state.contentText(), state.contentChecksum(), "PERIODIC");
    }
}
