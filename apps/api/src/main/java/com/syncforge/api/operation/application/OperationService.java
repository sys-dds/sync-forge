package com.syncforge.api.operation.application;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.syncforge.api.conflict.application.ConflictDetectionService;
import com.syncforge.api.conflict.model.ConflictResolutionResult;
import com.syncforge.api.documentstate.application.DocumentStateService;
import com.syncforge.api.delivery.RoomEventOutboxService;
import com.syncforge.api.operation.model.OfflineOperationSubmission;
import com.syncforge.api.operation.model.OfflineOperationSubmissionStatus;
import com.syncforge.api.operation.model.OperationRecord;
import com.syncforge.api.operation.model.OperationSubmitResult;
import com.syncforge.api.operation.model.RoomSequence;
import com.syncforge.api.operation.model.SubmitOperationCommand;
import com.syncforge.api.operation.store.OfflineOperationSubmissionRepository;
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
    private final OfflineOperationSubmissionRepository offlineSubmissionRepository;
    private final CanonicalOperationPayloadHasher payloadHasher;

    public OperationService(
            OperationRepository operationRepository,
            OperationAttemptRepository attemptRepository,
            RoomSequenceRepository sequenceRepository,
            RoomPermissionService permissionService,
            DocumentStateService documentStateService,
            ConflictDetectionService conflictDetectionService,
            RoomEventOutboxService outboxService,
            OfflineOperationSubmissionRepository offlineSubmissionRepository,
            CanonicalOperationPayloadHasher payloadHasher) {
        this.operationRepository = operationRepository;
        this.attemptRepository = attemptRepository;
        this.sequenceRepository = sequenceRepository;
        this.permissionService = permissionService;
        this.documentStateService = documentStateService;
        this.conflictDetectionService = conflictDetectionService;
        this.outboxService = outboxService;
        this.offlineSubmissionRepository = offlineSubmissionRepository;
        this.payloadHasher = payloadHasher;
    }

    @Transactional
    public OperationSubmitResult submit(SubmitOperationCommand command) {
        OperationSubmitResult validation = validate(command);
        if (validation != null) {
            return validation;
        }

        if (!permissionService.canEdit(command.roomId(), command.userId())) {
            recordRejected(command, "EDIT_PERMISSION_REQUIRED", "User does not have edit permission", null);
            recordOfflineRejected(command, "EDIT_PERMISSION_REQUIRED", "User does not have edit permission");
            return OperationSubmitResult.nack(command.operationId(), command.clientSeq(), "EDIT_PERMISSION_REQUIRED",
                    "User does not have edit permission", null);
        }

        if (!ALLOWED_TYPES.contains(command.operationType())) {
            recordRejected(command, "UNSUPPORTED_OPERATION_TYPE", "operationType is not supported", null);
            recordOfflineRejected(command, "UNSUPPORTED_OPERATION_TYPE", "operationType is not supported");
            return OperationSubmitResult.nack(command.operationId(), command.clientSeq(), "UNSUPPORTED_OPERATION_TYPE",
                    "operationType is not supported", null);
        }

        OperationSubmitResult offlineDuplicate = resolveOfflineDuplicate(command);
        if (offlineDuplicate != null) {
            return offlineDuplicate;
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
            recordOfflineRejected(command, "DUPLICATE_OPERATION_CONFLICT", "operationId already exists with different payload");
            return OperationSubmitResult.nack(command.operationId(), command.clientSeq(), "DUPLICATE_OPERATION_CONFLICT",
                    "operationId already exists with different payload", null);
        }

        RoomSequence sequence = sequenceRepository.lockForUpdate(command.roomId());
        String operationType = command.operationType();
        Map<String, Object> operation = command.operation();
        long effectiveBaseRevision = command.baseRevision();
        boolean transformed = false;
        OperationSubmitResult dependencyRejection = validateCausalDependencies(command);
        if (dependencyRejection != null) {
            return dependencyRejection;
        }
        if (command.baseRevision() > sequence.currentRevision()) {
            recordRejected(command, "STALE_BASE_REVISION", "baseRevision is ahead of current room revision",
                    sequence.currentRevision());
            recordOfflineRejected(command, "STALE_BASE_REVISION", "baseRevision is ahead of current room revision");
            return OperationSubmitResult.nack(command.operationId(), command.clientSeq(), "STALE_BASE_REVISION",
                    "baseRevision is ahead of current room revision", sequence.currentRevision());
        }
        if (command.offlineRequested() && command.baseRoomSeq() > sequence.currentRoomSeq()) {
            recordRejected(command, "BASE_ROOM_SEQ_AHEAD", "baseRoomSeq is ahead of current room sequence",
                    sequence.currentRevision());
            recordOfflineRejected(command, "BASE_ROOM_SEQ_AHEAD", "baseRoomSeq is ahead of current room sequence");
            return OperationSubmitResult.nack(command.operationId(), command.clientSeq(), "BASE_ROOM_SEQ_AHEAD",
                    "baseRoomSeq is ahead of current room sequence", sequence.currentRevision());
        }
        if (command.baseRevision() < sequence.currentRevision()) {
            ConflictResolutionResult conflictResult = conflictDetectionService.resolveStale(command, sequence.currentRevision());
            if (!conflictResult.accepted()) {
                recordRejected(command, conflictResult.code(), conflictResult.reason(), sequence.currentRevision());
                recordOfflineRejected(command, conflictResult.code(), conflictResult.reason());
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
            recordOfflineRejected(command, "INVALID_OPERATION_PAYLOAD", exception.getMessage());
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
        recordOfflineAccepted(command, inserted);
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
        if (command.offlineRequested()) {
            if (command.clientOperationId() == null || command.clientOperationId().isBlank()) {
                return OperationSubmitResult.nack(command.operationId(), command.clientSeq(), "INVALID_CLIENT_OPERATION_ID",
                        "clientOperationId is required for offline submissions", null);
            }
            if (command.baseRoomSeq() == null) {
                return OperationSubmitResult.nack(command.operationId(), command.clientSeq(), "INVALID_BASE_ROOM_SEQ",
                        "baseRoomSeq is required for offline submissions", null);
            }
            if (command.baseRoomSeq() < 0) {
                return OperationSubmitResult.nack(command.operationId(), command.clientSeq(), "INVALID_BASE_ROOM_SEQ",
                        "baseRoomSeq must be non-negative", null);
            }
            if (command.canonicalPayloadHash() == null || command.canonicalPayloadHash().isBlank()) {
                return OperationSubmitResult.nack(command.operationId(), command.clientSeq(), "INVALID_CANONICAL_PAYLOAD_HASH",
                        "canonicalPayloadHash is required for offline submissions", null);
            }
            if (!payloadHasher.matches(command.canonicalPayloadHash(), command.operationType(), command.operation())) {
                return OperationSubmitResult.nack(command.operationId(), command.clientSeq(), "INVALID_CANONICAL_PAYLOAD_HASH",
                        "canonicalPayloadHash does not match the submitted operation payload", null);
            }
        }
        if (command.dependsOnOperationIds().stream().anyMatch(dependency -> dependency == null || dependency.isBlank())) {
            return OperationSubmitResult.nack(command.operationId(), command.clientSeq(), "INVALID_CAUSAL_DEPENDENCY",
                    "dependsOnOperationIds must not contain blank values", null);
        }
        return null;
    }

    private OperationSubmitResult validateCausalDependencies(SubmitOperationCommand command) {
        if (!command.offlineRequested()) {
            return null;
        }
        if (command.dependsOnRoomSeq() != null) {
            if (command.dependsOnRoomSeq() <= 0) {
                return rejectCausalDependency(command, "INVALID_CAUSAL_DEPENDENCY",
                        "dependsOnRoomSeq must be positive when provided");
            }
            if (operationRepository.findByRoomSeq(command.roomId(), command.dependsOnRoomSeq()).isEmpty()) {
                return rejectCausalDependency(command, "CAUSAL_DEPENDENCY_MISSING",
                        "required room sequence dependency is not available");
            }
        }
        for (String dependencyOperationId : command.dependsOnOperationIds()) {
            if (operationRepository.findByRoomAndOperationId(command.roomId(), dependencyOperationId).isPresent()) {
                continue;
            }
            if (operationRepository.existsOperationIdOutsideRoom(dependencyOperationId, command.roomId())) {
                return rejectCausalDependency(command, "CAUSAL_DEPENDENCY_CROSS_ROOM",
                        "required operation dependency is not in this room");
            }
            return rejectCausalDependency(command, "CAUSAL_DEPENDENCY_MISSING",
                    "required operation dependency is not available");
        }
        return null;
    }

    private OperationSubmitResult rejectCausalDependency(SubmitOperationCommand command, String code, String reason) {
        recordRejected(command, code, reason, null);
        recordOfflineRejected(command, code, reason);
        return OperationSubmitResult.nack(command.operationId(), command.clientSeq(), code, reason, null);
    }

    private void recordRejected(SubmitOperationCommand command, String code, String message, Long currentRevision) {
        attemptRepository.record(command.roomId(), command.userId(), command.connectionId(), command.operationId(),
                command.clientSeq(), command.baseRevision(), command.operationType(), command.operation(), "REJECTED",
                code, message, null, currentRevision, null);
    }

    private OperationSubmitResult resolveOfflineDuplicate(SubmitOperationCommand command) {
        if (!offlinePersistenceReady(command)) {
            return null;
        }
        Optional<OfflineOperationSubmission> existing = offlineSubmissionRepository.findByClientOperation(
                command.roomId(), command.userId(), command.clientOperationId());
        if (existing.isEmpty()) {
            return null;
        }
        OfflineOperationSubmission submission = existing.get();
        if (!submission.canonicalPayloadHash().equals(command.canonicalPayloadHash())) {
            return OperationSubmitResult.nack(command.operationId(), command.clientSeq(), "OFFLINE_CLIENT_OPERATION_CONFLICT",
                    "clientOperationId already exists with a different canonical payload hash", null);
        }
        if (submission.status() == OfflineOperationSubmissionStatus.REJECTED) {
            return OperationSubmitResult.nack(command.operationId(), command.clientSeq(), submission.rejectionCode(),
                    submission.rejectionReason(), null);
        }
        OperationRecord accepted = operationRepository.findByRoomAndOperationId(command.roomId(), submission.acceptedOperationId())
                .orElseThrow();
        attemptRepository.record(command.roomId(), command.userId(), command.connectionId(), accepted.operationId(),
                command.clientSeq(), command.baseRevision(), accepted.operationType(), accepted.operation(), "DUPLICATE",
                null, "Duplicate offline operation returned original acknowledgement", accepted.roomSeq(),
                accepted.resultingRevision(), accepted.id());
        return OperationSubmitResult.ack(accepted.operationId(), accepted.clientSeq(), accepted.roomSeq(),
                accepted.resultingRevision(), true, accepted.operationType(), accepted.operation());
    }

    private void recordOfflineAccepted(SubmitOperationCommand command, OperationRecord operation) {
        if (!offlinePersistenceReady(command)) {
            return;
        }
        offlineSubmissionRepository.recordAccepted(command.roomId(), command.userId(), offlineClientId(command),
                command.clientOperationId(), command.baseRoomSeq(), command.baseRevision(), command.canonicalPayloadHash(),
                command.dependsOnOperationIds(), operation.operationId(), operation.roomSeq());
    }

    private void recordOfflineRejected(SubmitOperationCommand command, String code, String reason) {
        if (!offlinePersistenceReady(command)) {
            return;
        }
        offlineSubmissionRepository.recordRejected(command.roomId(), command.userId(), offlineClientId(command),
                command.clientOperationId(), command.baseRoomSeq(), command.baseRevision(), command.canonicalPayloadHash(),
                command.dependsOnOperationIds(), code, reason);
    }

    private boolean offlinePersistenceReady(SubmitOperationCommand command) {
        return command.offlineRequested()
                && command.clientOperationId() != null
                && !command.clientOperationId().isBlank()
                && command.baseRoomSeq() != null
                && command.baseRevision() != null
                && command.canonicalPayloadHash() != null
                && !command.canonicalPayloadHash().isBlank();
    }

    private String offlineClientId(SubmitOperationCommand command) {
        if (command.clientSessionId() != null && !command.clientSessionId().isBlank()) {
            return command.clientSessionId();
        }
        return command.connectionId();
    }
}
