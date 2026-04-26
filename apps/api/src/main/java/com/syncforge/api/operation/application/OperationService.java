package com.syncforge.api.operation.application;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.syncforge.api.conflict.application.ConflictDetectionService;
import com.syncforge.api.conflict.model.ConflictResolutionResult;
import com.syncforge.api.documentstate.application.DocumentStateService;
import com.syncforge.api.delivery.RoomEventOutboxService;
import com.syncforge.api.operation.model.OperationRecord;
import com.syncforge.api.operation.model.OperationSubmitResult;
import com.syncforge.api.operation.model.RoomSequence;
import com.syncforge.api.operation.model.SubmitOperationCommand;
import com.syncforge.api.operation.store.OperationAttemptRepository;
import com.syncforge.api.operation.store.OperationRepository;
import com.syncforge.api.operation.store.RoomSequenceRepository;
import com.syncforge.api.room.application.RoomPermissionService;
import com.syncforge.api.shared.BadRequestException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperationService {
    private static final Set<String> ALLOWED_TYPES = Set.of("TEXT_INSERT", "TEXT_DELETE", "TEXT_REPLACE", "NOOP");

    private final OperationRepository operationRepository;
    private final OperationAttemptRepository attemptRepository;
    private final RoomSequenceRepository sequenceRepository;
    private final RoomPermissionService permissionService;
    private final DocumentStateService documentStateService;
    private final ConflictDetectionService conflictDetectionService;
    private final RoomEventOutboxService outboxService;

    public OperationService(
            OperationRepository operationRepository,
            OperationAttemptRepository attemptRepository,
            RoomSequenceRepository sequenceRepository,
            RoomPermissionService permissionService,
            DocumentStateService documentStateService,
            ConflictDetectionService conflictDetectionService,
            RoomEventOutboxService outboxService) {
        this.operationRepository = operationRepository;
        this.attemptRepository = attemptRepository;
        this.sequenceRepository = sequenceRepository;
        this.permissionService = permissionService;
        this.documentStateService = documentStateService;
        this.conflictDetectionService = conflictDetectionService;
        this.outboxService = outboxService;
    }

    @Transactional
    public OperationSubmitResult submit(SubmitOperationCommand command) {
        OperationSubmitResult validation = validate(command);
        if (validation != null) {
            return validation;
        }

        if (!permissionService.canEdit(command.roomId(), command.userId())) {
            recordRejected(command, "EDIT_PERMISSION_REQUIRED", "User does not have edit permission", null);
            return OperationSubmitResult.nack(command.operationId(), command.clientSeq(), "EDIT_PERMISSION_REQUIRED",
                    "User does not have edit permission", null);
        }

        if (!ALLOWED_TYPES.contains(command.operationType())) {
            recordRejected(command, "UNSUPPORTED_OPERATION_TYPE", "operationType is not supported", null);
            return OperationSubmitResult.nack(command.operationId(), command.clientSeq(), "UNSUPPORTED_OPERATION_TYPE",
                    "operationType is not supported", null);
        }

        Optional<OperationRecord> existing = operationRepository.findByRoomAndOperationId(command.roomId(), command.operationId());
        if (existing.isPresent()) {
            OperationRecord record = existing.get();
            if (operationRepository.sameOperation(record, command.operationType(), command.operation())) {
                attemptRepository.record(command.roomId(), command.userId(), command.connectionId(), command.operationId(),
                        command.clientSeq(), command.baseRevision(), command.operationType(), command.operation(), "DUPLICATE",
                        null, "Duplicate operation returned original acknowledgement", record.roomSeq(), record.resultingRevision(),
                        record.id());
                return OperationSubmitResult.ack(record.operationId(), record.clientSeq(), record.roomSeq(), record.resultingRevision(),
                        true, record.operationType(), record.operation());
            }
            recordRejected(command, "DUPLICATE_OPERATION_CONFLICT", "operationId already exists with different payload", null);
            return OperationSubmitResult.nack(command.operationId(), command.clientSeq(), "DUPLICATE_OPERATION_CONFLICT",
                    "operationId already exists with different payload", null);
        }

        RoomSequence sequence = sequenceRepository.lockForUpdate(command.roomId());
        String operationType = command.operationType();
        Map<String, Object> operation = command.operation();
        long effectiveBaseRevision = command.baseRevision();
        boolean transformed = false;
        if (command.baseRevision() > sequence.currentRevision()) {
            recordRejected(command, "STALE_BASE_REVISION", "baseRevision is ahead of current room revision",
                    sequence.currentRevision());
            return OperationSubmitResult.nack(command.operationId(), command.clientSeq(), "STALE_BASE_REVISION",
                    "baseRevision is ahead of current room revision", sequence.currentRevision());
        }
        if (command.baseRevision() < sequence.currentRevision()) {
            ConflictResolutionResult conflictResult = conflictDetectionService.resolveStale(command, sequence.currentRevision());
            if (!conflictResult.accepted()) {
                recordRejected(command, conflictResult.code(), conflictResult.reason(), sequence.currentRevision());
                return OperationSubmitResult.nack(command.operationId(), command.clientSeq(), conflictResult.code(),
                        conflictResult.reason(), sequence.currentRevision());
            }
            operationType = conflictResult.operationType();
            operation = conflictResult.operation();
            effectiveBaseRevision = sequence.currentRevision();
            transformed = conflictResult.transformed();
        }

        try {
            documentStateService.previewApply(command.roomId(), operationType, operation, sequence.currentRevision());
        } catch (BadRequestException exception) {
            recordRejected(command, "INVALID_OPERATION_PAYLOAD", exception.getMessage(), sequence.currentRevision());
            return OperationSubmitResult.nack(command.operationId(), command.clientSeq(), "INVALID_OPERATION_PAYLOAD",
                    exception.getMessage(), sequence.currentRevision());
        }

        long nextRoomSeq = sequence.currentRoomSeq() + 1;
        long nextRevision = sequence.currentRevision() + 1;
        OperationRecord inserted = operationRepository.insert(command.roomId(), command.userId(), command.connectionId(),
                command.operationId(), command.clientSessionId(), command.clientSeq(), effectiveBaseRevision,
                nextRoomSeq, nextRevision, operationType, operation);
        sequenceRepository.advance(command.roomId(), nextRoomSeq, nextRevision);
        documentStateService.applyAcceptedOperation(inserted);
        attemptRepository.record(command.roomId(), command.userId(), command.connectionId(), command.operationId(),
                command.clientSeq(), command.baseRevision(), command.operationType(), command.operation(), "ACCEPTED",
                null, null, inserted.roomSeq(), inserted.resultingRevision(), null);
        outboxService.createPendingOperationEvent(inserted, transformed);
        return OperationSubmitResult.ack(inserted.operationId(), inserted.clientSeq(), inserted.roomSeq(), inserted.resultingRevision(),
                false, inserted.operationType(), inserted.operation(), transformed);
    }

    private OperationSubmitResult validate(SubmitOperationCommand command) {
        if (command.operationId() == null || command.operationId().isBlank()) {
            return OperationSubmitResult.nack(command.operationId(), command.clientSeq(), "INVALID_OPERATION",
                    "operationId is required", null);
        }
        if (command.clientSeq() == null || command.clientSeq() <= 0) {
            return OperationSubmitResult.nack(command.operationId(), command.clientSeq(), "INVALID_CLIENT_SEQ",
                    "clientSeq is required and must be positive", null);
        }
        if (command.baseRevision() == null || command.baseRevision() < 0) {
            return OperationSubmitResult.nack(command.operationId(), command.clientSeq(), "INVALID_BASE_REVISION",
                    "baseRevision is required and must be non-negative", null);
        }
        if (command.operationType() == null || command.operationType().isBlank()) {
            return OperationSubmitResult.nack(command.operationId(), command.clientSeq(), "INVALID_OPERATION_TYPE",
                    "operationType is required", null);
        }
        if (command.operation() == null) {
            return OperationSubmitResult.nack(command.operationId(), command.clientSeq(), "INVALID_OPERATION",
                    "operation payload is required", null);
        }
        return null;
    }

    private void recordRejected(SubmitOperationCommand command, String code, String message, Long currentRevision) {
        attemptRepository.record(command.roomId(), command.userId(), command.connectionId(), command.operationId(),
                command.clientSeq(), command.baseRevision(), command.operationType(), command.operation(), "REJECTED",
                code, message, null, currentRevision, null);
    }
}
