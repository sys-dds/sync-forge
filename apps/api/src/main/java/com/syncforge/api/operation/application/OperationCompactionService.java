package com.syncforge.api.operation.application;

import java.util.UUID;

import com.syncforge.api.operation.store.OperationRepository.CompactionRunRecord;
import com.syncforge.api.operation.store.OperationRepository;
import com.syncforge.api.resume.application.ResumeWindowService;
import com.syncforge.api.resume.model.ResumeWindow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperationCompactionService {
    private final OperationRepository operationRepository;
    private final ResumeWindowService resumeWindowService;

    public OperationCompactionService(
            OperationRepository operationRepository,
            ResumeWindowService resumeWindowService) {
        this.operationRepository = operationRepository;
        this.resumeWindowService = resumeWindowService;
    }

    public CompactionPreview preview(UUID roomId) {
        ResumeWindow window = resumeWindowService.window(roomId);
        long compactableCount = window.snapshotRoomSeq() == 0
                ? 0
                : operationRepository.countActiveThroughRoomSeq(roomId, window.minimumResumableRoomSeq());
        long compactedCount = operationRepository.countCompactedThroughRoomSeq(roomId, window.minimumResumableRoomSeq());
        long activeTailCount = operationRepository.countActiveAfterRoomSeq(roomId, window.minimumResumableRoomSeq());
        boolean safeToCompact = window.snapshotRoomSeq() > 0 && compactableCount > 0;
        String reason = safeToCompact
                ? "SNAPSHOT_BOUNDARY_HAS_COMPACTABLE_HISTORY"
                : window.snapshotRoomSeq() == 0
                        ? "NO_SNAPSHOT_BOUNDARY"
                        : "NO_COMPACTABLE_OPERATIONS";
        return new CompactionPreview(
                roomId,
                window.latestRoomSeq(),
                window.snapshotRoomSeq(),
                window.minimumResumableRoomSeq(),
                compactedCount,
                activeTailCount,
                compactableCount,
                safeToCompact,
                reason,
                operationRepository.findLatestCompactionRun(roomId).orElse(null));
    }

    @Transactional
    public CompactionResult compactSafeHistory(UUID roomId) {
        CompactionPreview preview = preview(roomId);
        UUID runId = UUID.randomUUID();
        long compactionBoundary = preview.snapshotRoomSeq() == 0 ? -1 : preview.minimumResumableRoomSeq();
        int compactedCount = operationRepository.markCompactedThroughRoomSeq(
                roomId,
                compactionBoundary,
                runId);
        int activeTailCount = Math.toIntExact(operationRepository.countActiveAfterRoomSeq(
                roomId,
                preview.minimumResumableRoomSeq()));
        operationRepository.recordCompactionRun(
                runId,
                roomId,
                preview.minimumResumableRoomSeq(),
                preview.snapshotRoomSeq(),
                compactedCount,
                activeTailCount);
        return new CompactionResult(
                runId,
                roomId,
                preview.minimumResumableRoomSeq(),
                preview.snapshotRoomSeq(),
                compactedCount,
                activeTailCount,
                "COMPLETED",
                compactedCount == 0
                        ? preview.snapshotRoomSeq() == 0
                                ? "NO_SNAPSHOT_BOUNDARY_COMPACTION_SKIPPED"
                                : "NO_COMPACTABLE_OPERATIONS"
                        : "COMPACTED_SAFE_HISTORY");
    }

    public record CompactionPreview(
            UUID roomId,
            long latestRoomSeq,
            long snapshotRoomSeq,
            long minimumResumableRoomSeq,
            long compactedOperationCount,
            long activeTailCount,
            long compactableOperationCount,
            boolean safeToCompact,
            String reason,
            CompactionRunRecord lastCompactionRun
    ) {
    }

    public record CompactionResult(
            UUID runId,
            UUID roomId,
            long minimumResumableRoomSeq,
            long snapshotRoomSeq,
            int compactedCount,
            int activeTailCount,
            String status,
            String message
    ) {
    }
}
