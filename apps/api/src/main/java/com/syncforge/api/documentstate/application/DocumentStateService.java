package com.syncforge.api.documentstate.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import com.syncforge.api.documentstate.model.AppliedTextOperation;
import com.syncforge.api.documentstate.model.DocumentLiveState;
import com.syncforge.api.documentstate.store.DocumentStateRepository;
import com.syncforge.api.operation.model.OperationRecord;
import com.syncforge.api.operation.store.OperationRepository;
import com.syncforge.api.room.model.Room;
import com.syncforge.api.room.store.RoomRepository;
import com.syncforge.api.text.application.TextConvergenceService;
import com.syncforge.api.shared.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentStateService {
    private final DocumentStateRepository documentStateRepository;
    private final OperationRepository operationRepository;
    private final RoomRepository roomRepository;
    private final TextOperationApplier textOperationApplier;
    private final TextConvergenceService textConvergenceService;

    public DocumentStateService(
            DocumentStateRepository documentStateRepository,
            OperationRepository operationRepository,
            RoomRepository roomRepository,
            TextOperationApplier textOperationApplier,
            TextConvergenceService textConvergenceService) {
        this.documentStateRepository = documentStateRepository;
        this.operationRepository = operationRepository;
        this.roomRepository = roomRepository;
        this.textOperationApplier = textOperationApplier;
        this.textConvergenceService = textConvergenceService;
    }

    @Transactional
    public DocumentLiveState getOrInitialize(UUID roomId) {
        Room room = requireRoom(roomId);
        return documentStateRepository.initializeIfMissing(room.id(), room.documentId(), checksum(""));
    }

    @Transactional
    public DocumentLiveState applyAcceptedOperation(OperationRecord operation) {
        DocumentLiveState state = getOrInitialize(operation.roomId());
        if (operation.resultingRevision() <= state.currentRevision()) {
            return state;
        }
        if (operation.baseRevision() != state.currentRevision()) {
            throw new IllegalStateException("Operation base revision does not match materialized state revision");
        }
        AppliedTextOperation applied = textConvergenceService.supports(operation.operationType())
                ? textConvergenceService.applyAccepted(operation)
                : textOperationApplier.apply(state.contentText(), operation.operationType(), operation.operation());
        return documentStateRepository.updateState(
                operation.roomId(),
                operation.roomSeq(),
                operation.resultingRevision(),
                applied.content(),
                checksum(applied.content()),
                operation.id(),
                null,
                null);
    }

    public AppliedTextOperation previewApply(UUID roomId, String operationType, java.util.Map<String, Object> operation, long expectedRevision) {
        DocumentLiveState state = getOrInitialize(roomId);
        if (state.currentRevision() != expectedRevision) {
            throw new IllegalStateException("Materialized state revision does not match room sequence revision");
        }
        if (textConvergenceService.supports(operationType)) {
            return textConvergenceService.previewApply(roomId, operationType, operation);
        }
        return textOperationApplier.apply(state.contentText(), operationType, operation);
    }

    @Transactional
    public RebuildResult rebuildFromOperationLog(UUID roomId) {
        Room room = requireRoom(roomId);
        DocumentLiveState before = getOrInitialize(roomId);
        UUID rebuildId = documentStateRepository.startRebuild(room.id(), room.documentId(), null);
        try {
            ReplayResult replay = replayOperations(operationRepository.findByRoom(roomId), "");
            DocumentLiveState rebuilt = documentStateRepository.updateState(
                    roomId,
                    replay.roomSeq(),
                    replay.revision(),
                    replay.content(),
                    checksum(replay.content()),
                    replay.lastOperationId(),
                    null,
                    OffsetDateTime.now());
            documentStateRepository.completeRebuild(rebuildId, replay.operationsReplayed(), rebuilt);
            boolean equivalent = before.currentRevision() == rebuilt.currentRevision()
                    && before.currentRoomSeq() == rebuilt.currentRoomSeq()
                    && before.contentChecksum().equals(rebuilt.contentChecksum());
            return new RebuildResult(rebuilt, replay.operationsReplayed(), equivalent);
        } catch (RuntimeException exception) {
            documentStateRepository.failRebuild(rebuildId, exception.getMessage());
            throw exception;
        }
    }

    public boolean verifyFullReplayEquivalence(UUID roomId) {
        DocumentLiveState state = getOrInitialize(roomId);
        ReplayResult replay = replayOperations(operationRepository.findByRoom(roomId), "");
        return state.currentRoomSeq() == replay.roomSeq()
                && state.currentRevision() == replay.revision()
                && state.contentChecksum().equals(checksum(replay.content()));
    }

    public ReplayResult replayOperations(List<OperationRecord> operations, String initialContent) {
        if (operations.stream().anyMatch(operation -> textConvergenceService.supports(operation.operationType()))) {
            if (initialContent != null && !initialContent.isEmpty() && !operations.isEmpty()) {
                OperationRecord last = operations.get(operations.size() - 1);
                String content = textConvergenceService.materializeVisibleText(last.roomId());
                return new ReplayResult(content, last.roomSeq(), last.resultingRevision(), last.id(), operations.size());
            }
            var replay = textConvergenceService.replay(operations);
            return new ReplayResult(
                    replay.content(),
                    replay.roomSeq(),
                    replay.revision(),
                    replay.lastOperationId(),
                    replay.operationsReplayed());
        }
        String content = initialContent == null ? "" : initialContent;
        long roomSeq = 0;
        long revision = 0;
        UUID lastOperationId = null;
        int operationsReplayed = 0;
        for (OperationRecord operation : operations) {
            AppliedTextOperation applied = textOperationApplier.apply(content, operation.operationType(), operation.operation());
            content = applied.content();
            roomSeq = operation.roomSeq();
            revision = operation.resultingRevision();
            lastOperationId = operation.id();
            operationsReplayed++;
        }
        return new ReplayResult(content, roomSeq, revision, lastOperationId, operationsReplayed);
    }

    public String checksum(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((content == null ? "" : content).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private Room requireRoom(UUID roomId) {
        return roomRepository.findById(roomId)
                .orElseThrow(() -> new NotFoundException("ROOM_NOT_FOUND", "Room not found"));
    }

    public record RebuildResult(DocumentLiveState state, int operationsReplayed, boolean replayEquivalent) {
    }

    public record ReplayResult(String content, long roomSeq, long revision, UUID lastOperationId, int operationsReplayed) {
    }
}
