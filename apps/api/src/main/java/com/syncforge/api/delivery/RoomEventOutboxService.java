package com.syncforge.api.delivery;

import java.util.Optional;
import java.util.UUID;

import com.syncforge.api.operation.model.OperationRecord;
import org.springframework.stereotype.Service;

@Service
public class RoomEventOutboxService {
    private final RoomEventPayloadFactory payloadFactory;
    private final RoomEventOutboxRepository outboxRepository;

    public RoomEventOutboxService(
            RoomEventPayloadFactory payloadFactory,
            RoomEventOutboxRepository outboxRepository) {
        this.payloadFactory = payloadFactory;
        this.outboxRepository = outboxRepository;
    }

    public RoomEventOutboxRecord createPendingOperationEvent(OperationRecord operation, boolean transformed) {
        String logicalEventKey = payloadFactory.logicalEventKey(operation.roomId().toString(), operation.roomSeq());
        return outboxRepository.insertPendingOperationEvent(
                UUID.randomUUID(),
                operation.roomId(),
                operation.roomSeq(),
                operation.resultingRevision(),
                operation.operationId(),
                logicalEventKey,
                payloadFactory.operationApplied(operation, transformed),
                operation.ownerNodeId(),
                operation.fencingToken());
    }

    public Optional<RoomEventOutboxRecord> findByRoomSeq(UUID roomId, long roomSeq) {
        return outboxRepository.findByRoomSeq(roomId, roomSeq);
    }

    public long countByStatus(RoomEventOutboxStatus status) {
        return outboxRepository.countByStatus(status);
    }
}
