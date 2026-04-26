package com.syncforge.api.conflict.application;

import java.util.List;

import com.syncforge.api.conflict.model.ConflictResolutionResult;
import com.syncforge.api.operation.model.OperationRecord;
import com.syncforge.api.operation.model.SubmitOperationCommand;
import com.syncforge.api.operation.store.OperationRepository;
import org.springframework.stereotype.Service;

@Service
public class ConflictDetectionService {
    private final OperationRepository operationRepository;
    private final OperationalTransformService operationalTransformService;
    private final ConflictTraceService conflictTraceService;

    public ConflictDetectionService(
            OperationRepository operationRepository,
            OperationalTransformService operationalTransformService,
            ConflictTraceService conflictTraceService) {
        this.operationRepository = operationRepository;
        this.operationalTransformService = operationalTransformService;
        this.conflictTraceService = conflictTraceService;
    }

    public ConflictResolutionResult resolveStale(SubmitOperationCommand command, long currentRevision) {
        List<OperationRecord> concurrentOperations = operationRepository.findByRoomAfterRevision(command.roomId(), command.baseRevision());
        ConflictResolutionResult result = operationalTransformService.transform(command, concurrentOperations);
        conflictTraceService.record(command, currentRevision, result.decision(), result.reason(), concurrentOperations,
                result.accepted() ? result.operation() : null);
        return result;
    }
}
