package com.syncforge.api.operation.application;

import java.util.UUID;

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

    @Transactional
    public CompactionResult compactSafeHistory(UUID roomId) {
        ResumeWindow window = resumeWindowService.window(roomId);
        UUID runId = UUID.randomUUID();
        int compactedCount = operationRepository.markCompactedThroughRoomSeq(
                roomId,
                window.minimumResumableRoomSeq(),
                runId);
        int activeTailCount = Math.toIntExact(operationRepository.countActiveAfterRoomSeq(
                roomId,
                window.minimumResumableRoomSeq()));
        operationRepository.recordCompactionRun(
                runId,
                roomId,
                window.minimumResumableRoomSeq(),
                window.snapshotRoomSeq(),
                compactedCount,
                activeTailCount);
        return new CompactionResult(
                runId,
                roomId,
                window.minimumResumableRoomSeq(),
                window.snapshotRoomSeq(),
                compactedCount,
                activeTailCount);
    }

    public record CompactionResult(
            UUID runId,
            UUID roomId,
            long minimumResumableRoomSeq,
            long snapshotRoomSeq,
            int compactedCount,
            int activeTailCount
    ) {
    }
}
