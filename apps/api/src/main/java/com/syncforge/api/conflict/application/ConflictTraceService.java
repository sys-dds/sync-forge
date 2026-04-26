package com.syncforge.api.conflict.application;

import java.util.List;
import java.util.Map;

import com.syncforge.api.conflict.store.ConflictTraceRepository;
import com.syncforge.api.operation.model.OperationRecord;
import com.syncforge.api.operation.model.SubmitOperationCommand;
import org.springframework.stereotype.Service;

@Service
public class ConflictTraceService {
    private final ConflictTraceRepository conflictTraceRepository;

    public ConflictTraceService(ConflictTraceRepository conflictTraceRepository) {
        this.conflictTraceRepository = conflictTraceRepository;
    }

    public void record(
            SubmitOperationCommand command,
            long currentRevision,
            String decision,
            String reason,
            List<OperationRecord> concurrentOperations,
            Map<String, Object> transformedOperation) {
        conflictTraceRepository.record(command, currentRevision, decision, reason, concurrentOperations, transformedOperation);
    }
}
