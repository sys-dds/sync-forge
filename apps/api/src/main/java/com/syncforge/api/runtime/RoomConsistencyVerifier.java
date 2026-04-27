package com.syncforge.api.runtime;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.syncforge.api.delivery.RoomEventOutboxRepository;
import com.syncforge.api.documentstate.application.DocumentStateService;
import com.syncforge.api.documentstate.model.DocumentLiveState;
import com.syncforge.api.operation.model.OperationRecord;
import com.syncforge.api.operation.store.OperationRepository;
import com.syncforge.api.ownership.RoomOwnershipLease;
import com.syncforge.api.ownership.RoomOwnershipService;
import com.syncforge.api.resume.application.ResumeWindowService;
import com.syncforge.api.resume.model.ResumeWindow;
import com.syncforge.api.snapshot.model.DocumentSnapshot;
import com.syncforge.api.snapshot.store.SnapshotRepository;
import com.syncforge.api.text.application.TextConvergenceService;
import com.syncforge.api.text.model.TextAtom;
import com.syncforge.api.text.store.TextConvergenceRepository;
import org.springframework.stereotype.Service;

@Service
public class RoomConsistencyVerifier {
    private final OperationRepository operationRepository;
    private final RoomEventOutboxRepository outboxRepository;
    private final DocumentStateService documentStateService;
    private final TextConvergenceService textConvergenceService;
    private final TextConvergenceRepository textConvergenceRepository;
    private final SnapshotRepository snapshotRepository;
    private final ResumeWindowService resumeWindowService;
    private final RoomOwnershipService ownershipService;

    public RoomConsistencyVerifier(
            OperationRepository operationRepository,
            RoomEventOutboxRepository outboxRepository,
            DocumentStateService documentStateService,
            TextConvergenceService textConvergenceService,
            TextConvergenceRepository textConvergenceRepository,
            SnapshotRepository snapshotRepository,
            ResumeWindowService resumeWindowService,
            RoomOwnershipService ownershipService) {
        this.operationRepository = operationRepository;
        this.outboxRepository = outboxRepository;
        this.documentStateService = documentStateService;
        this.textConvergenceService = textConvergenceService;
        this.textConvergenceRepository = textConvergenceRepository;
        this.snapshotRepository = snapshotRepository;
        this.resumeWindowService = resumeWindowService;
        this.ownershipService = ownershipService;
    }

    public RoomInvariantSnapshot verify(UUID roomId) {
        DocumentLiveState state = documentStateService.getOrInitialize(roomId);
        List<OperationRecord> operations = operationRepository.findByRoom(roomId);
        List<RoomInvariantViolation> violations = new ArrayList<>();
        verifySequences(operations, violations);
        verifyDocumentState(roomId, state, operations, violations);
        verifyTextMaterialization(roomId, state, violations);
        verifySnapshotReplay(roomId, state, violations);
        verifyOutbox(roomId, operations, violations);
        verifyOwnership(roomId, violations);
        verifyResumeWindow(roomId, state, violations);
        return new RoomInvariantSnapshot(
                roomId,
                violations.isEmpty() ? RoomInvariantStatus.PASS : RoomInvariantStatus.FAIL,
                OffsetDateTime.now(),
                state.currentRoomSeq(),
                state.currentRevision(),
                violations.size(),
                violations);
    }

    private void verifySequences(List<OperationRecord> operations, List<RoomInvariantViolation> violations) {
        Set<Long> roomSeqs = new HashSet<>();
        Set<String> operationIds = new HashSet<>();
        long expected = 1;
        for (OperationRecord operation : operations) {
            if (!roomSeqs.add(operation.roomSeq())) {
                violations.add(violation("DUPLICATE_ROOM_SEQ", "ERROR", "roomSeq must be unique",
                        "unique", Long.toString(operation.roomSeq()), operation.roomSeq()));
            }
            if (!operationIds.add(operation.operationId())) {
                violations.add(violation("DUPLICATE_OPERATION_ID", "ERROR", "operationId must be unique per room",
                        "unique", operation.operationId(), operation.roomSeq()));
            }
            if (operation.roomSeq() != expected) {
                violations.add(violation("ROOM_SEQ_GAP", "ERROR", "canonical roomSeq must be gapless",
                        Long.toString(expected), Long.toString(operation.roomSeq()), operation.roomSeq()));
                expected = operation.roomSeq();
            }
            expected++;
        }
    }

    private void verifyDocumentState(
            UUID roomId,
            DocumentLiveState state,
            List<OperationRecord> operations,
            List<RoomInvariantViolation> violations) {
        long maxRoomSeq = operations.stream().mapToLong(OperationRecord::roomSeq).max().orElse(0L);
        long maxRevision = operations.stream().mapToLong(OperationRecord::resultingRevision).max().orElse(0L);
        if (state.currentRoomSeq() != maxRoomSeq) {
            violations.add(violation("DOCUMENT_STATE_ROOM_SEQ_MISMATCH", "ERROR",
                    "document state roomSeq must match canonical log",
                    Long.toString(maxRoomSeq), Long.toString(state.currentRoomSeq()), maxRoomSeq));
        }
        if (state.currentRevision() != maxRevision) {
            violations.add(violation("DOCUMENT_STATE_REVISION_MISMATCH", "ERROR",
                    "document state revision must match canonical log",
                    Long.toString(maxRevision), Long.toString(state.currentRevision()), maxRoomSeq));
        }
        String checksum = documentStateService.checksum(state.contentText());
        if (!checksum.equals(state.contentChecksum())) {
            violations.add(violation("DOCUMENT_STATE_CHECKSUM_MISMATCH", "ERROR",
                    "document state checksum must match visible text",
                    checksum, state.contentChecksum(), state.currentRoomSeq()));
        }
    }

    private void verifyTextMaterialization(UUID roomId, DocumentLiveState state, List<RoomInvariantViolation> violations) {
        String visibleText = textConvergenceService.materializeVisibleText(roomId);
        if (!visibleText.equals(state.contentText())) {
            violations.add(violation("TEXT_MATERIALIZATION_MISMATCH", "ERROR",
                    "text atoms must materialize to document state content",
                    state.contentText(), visibleText, state.currentRoomSeq()));
        }
    }

    private void verifySnapshotReplay(UUID roomId, DocumentLiveState state, List<RoomInvariantViolation> violations) {
        DocumentSnapshot snapshot = snapshotRepository.findLatest(roomId).orElse(null);
        if (snapshot == null) {
            return;
        }
        List<OperationRecord> tail = operationRepository.findActiveByRoomAfterRoomSeq(roomId, snapshot.roomSeq());
        List<TextAtom> atoms = textConvergenceRepository.listSnapshotAtoms(snapshot.id());
        try {
            DocumentStateService.ReplayResult replay = documentStateService.replayTextFromSnapshotAtoms(
                    atoms,
                    snapshot.roomSeq(),
                    snapshot.revision(),
                    operationRepository.findByRoomSeq(roomId, snapshot.roomSeq()).map(OperationRecord::id).orElse(null),
                    tail);
            if (!replay.content().equals(state.contentText())) {
                violations.add(violation("SNAPSHOT_TAIL_REPLAY_MISMATCH", "ERROR",
                        "snapshot plus active tail must equal live visible text",
                        state.contentText(), replay.content(), state.currentRoomSeq()));
            }
        } catch (RuntimeException exception) {
            violations.add(violation("SNAPSHOT_TAIL_REPLAY_FAILED", "ERROR",
                    "snapshot plus active tail replay must not fail",
                    "success", exception.getMessage(), snapshot.roomSeq()));
        }
    }

    private void verifyOutbox(UUID roomId, List<OperationRecord> operations, List<RoomInvariantViolation> violations) {
        for (OperationRecord operation : operations) {
            if (outboxRepository.findByRoomSeq(roomId, operation.roomSeq()).isEmpty()) {
                violations.add(violation("MISSING_OUTBOX_ROW", "ERROR",
                        "accepted operations must have delivery outbox rows",
                        "outbox row", "missing", operation.roomSeq()));
            }
        }
    }

    private void verifyOwnership(UUID roomId, List<RoomInvariantViolation> violations) {
        RoomOwnershipLease lease = ownershipService.currentOwnership(roomId);
        if (lease == null) {
            return;
        }
        if (lease.fencingToken() <= 0) {
            violations.add(violation("INVALID_FENCING_TOKEN", "ERROR",
                    "fencing token must be positive", "positive", Long.toString(lease.fencingToken()), null));
        }
        long missingOwnerMetadata = operationRepository.countMissingOwnerMetadata(roomId);
        if (missingOwnerMetadata > 0) {
            violations.add(violation("OPERATION_OWNER_METADATA_MISSING", "WARN",
                    "accepted operations should carry owner metadata after ownership is enabled",
                    "0", Long.toString(missingOwnerMetadata), null));
        }
    }

    private void verifyResumeWindow(UUID roomId, DocumentLiveState state, List<RoomInvariantViolation> violations) {
        ResumeWindow window = resumeWindowService.window(roomId);
        if (window.minimumResumableRoomSeq() > state.currentRoomSeq()) {
            violations.add(violation("MINIMUM_RESUMABLE_AFTER_LATEST", "ERROR",
                    "minimum resumable roomSeq must not exceed latest roomSeq",
                    "<= " + state.currentRoomSeq(), Long.toString(window.minimumResumableRoomSeq()), null));
        }
        if (window.snapshotRoomSeq() > state.currentRoomSeq()) {
            violations.add(violation("SNAPSHOT_AFTER_LATEST", "ERROR",
                    "snapshot roomSeq must not exceed latest roomSeq",
                    "<= " + state.currentRoomSeq(), Long.toString(window.snapshotRoomSeq()), null));
        }
    }

    private RoomInvariantViolation violation(
            String code,
            String severity,
            String message,
            String expected,
            String actual,
            Long relatedRoomSeq) {
        return new RoomInvariantViolation(code, severity, message, expected, actual, relatedRoomSeq);
    }
}
