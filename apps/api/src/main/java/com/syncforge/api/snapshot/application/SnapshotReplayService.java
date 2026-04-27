package com.syncforge.api.snapshot.application;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.syncforge.api.documentstate.application.DocumentStateService;
import com.syncforge.api.documentstate.model.DocumentLiveState;
import com.syncforge.api.documentstate.store.DocumentStateRepository;
import com.syncforge.api.operation.model.OperationRecord;
import com.syncforge.api.operation.store.OperationRepository;
import com.syncforge.api.shared.BadRequestException;
import com.syncforge.api.snapshot.api.SnapshotReplayResponse;
import com.syncforge.api.snapshot.model.DocumentSnapshot;
import com.syncforge.api.text.model.TextAtom;
import com.syncforge.api.text.store.TextConvergenceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SnapshotReplayService {
    private final SnapshotService snapshotService;
    private final DocumentStateService documentStateService;
    private final DocumentStateRepository documentStateRepository;
    private final OperationRepository operationRepository;
    private final TextConvergenceRepository textConvergenceRepository;

    public SnapshotReplayService(
            SnapshotService snapshotService,
            DocumentStateService documentStateService,
            DocumentStateRepository documentStateRepository,
            OperationRepository operationRepository,
            TextConvergenceRepository textConvergenceRepository) {
        this.snapshotService = snapshotService;
        this.documentStateService = documentStateService;
        this.documentStateRepository = documentStateRepository;
        this.operationRepository = operationRepository;
        this.textConvergenceRepository = textConvergenceRepository;
    }

    @Transactional
    public SnapshotReplayResponse replayFromLatestSnapshot(UUID roomId) {
        DocumentSnapshot snapshot = snapshotService.getLatestSnapshot(roomId);
        String expectedSnapshotChecksum = documentStateService.checksum(snapshot.contentText());
        if (!expectedSnapshotChecksum.equals(snapshot.contentChecksum())) {
            throw new BadRequestException("SNAPSHOT_CHECKSUM_MISMATCH", "Snapshot checksum does not match snapshot content");
        }
        List<OperationRecord> operations = operationRepository.findByRoomAfterRoomSeq(roomId, snapshot.roomSeq());
        List<TextAtom> snapshotAtoms = textConvergenceRepository.listSnapshotAtoms(snapshot.id());
        UUID snapshotLastOperationId = operationRepository.findByRoomSeq(roomId, snapshot.roomSeq())
                .map(OperationRecord::id)
                .orElse(null);
        UUID rebuildId = documentStateRepository.startRebuild(roomId, snapshot.documentId(), snapshot.id());
        try {
            DocumentStateService.ReplayResult replay = operations.stream()
                    .anyMatch(operation -> "TEXT_INSERT_AFTER".equals(operation.operationType())
                            || "TEXT_DELETE_ATOMS".equals(operation.operationType()))
                    || !snapshotAtoms.isEmpty()
                    ? documentStateService.replayTextFromSnapshotAtoms(
                            snapshotAtoms,
                            snapshot.roomSeq(),
                            snapshot.revision(),
                            snapshotLastOperationId,
                            operations)
                    : documentStateService.replayOperations(operations, snapshot.contentText());
            DocumentLiveState rebuilt = documentStateRepository.updateState(
                    roomId,
                    replay.roomSeq() == 0 ? snapshot.roomSeq() : replay.roomSeq(),
                    replay.revision() == 0 ? snapshot.revision() : replay.revision(),
                    replay.content(),
                    documentStateService.checksum(replay.content()),
                    replay.lastOperationId(),
                    snapshot.id(),
                    OffsetDateTime.now());
            documentStateRepository.completeRebuild(rebuildId, replay.operationsReplayed(), rebuilt);
            boolean equivalent = documentStateService.verifyFullReplayEquivalence(roomId);
            return new SnapshotReplayResponse(roomId, snapshot.id(), replay.operationsReplayed(),
                    rebuilt.currentRoomSeq(), rebuilt.currentRevision(), rebuilt.contentChecksum(), true, equivalent);
        } catch (RuntimeException exception) {
            documentStateRepository.failRebuild(rebuildId, exception.getMessage());
            documentStateRepository.recordFailedRebuild(roomId, snapshot.documentId(), snapshot.id(), exception.getMessage());
            throw exception;
        }
    }
}
