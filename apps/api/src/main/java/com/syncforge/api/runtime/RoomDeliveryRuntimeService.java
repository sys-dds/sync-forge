package com.syncforge.api.runtime;

import java.util.UUID;

import com.syncforge.api.delivery.RoomEventOutboxDispatcher;
import com.syncforge.api.delivery.RoomEventOutboxRepository;
import com.syncforge.api.delivery.RoomEventOutboxStatus;
import com.syncforge.api.operation.store.OperationRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class RoomDeliveryRuntimeService {
    private final RoomEventOutboxRepository outboxRepository;
    private final RoomEventOutboxDispatcher outboxDispatcher;
    private final OperationRepository operationRepository;
    private final int drainLimit;

    public RoomDeliveryRuntimeService(
            RoomEventOutboxRepository outboxRepository,
            RoomEventOutboxDispatcher outboxDispatcher,
            OperationRepository operationRepository,
            @Value("${syncforge.runtime.delivery-drain-limit:100}") int drainLimit) {
        this.outboxRepository = outboxRepository;
        this.outboxDispatcher = outboxDispatcher;
        this.operationRepository = operationRepository;
        this.drainLimit = drainLimit;
    }

    public RoomDeliveryRuntimeResponse status(UUID roomId) {
        long pending = outboxRepository.countByRoomAndStatus(roomId, RoomEventOutboxStatus.PENDING);
        long retry = outboxRepository.countByRoomAndStatus(roomId, RoomEventOutboxStatus.RETRY);
        long published = outboxRepository.countByRoomAndStatus(roomId, RoomEventOutboxStatus.PUBLISHED);
        long parked = outboxRepository.countByRoomAndStatus(roomId, RoomEventOutboxStatus.PARKED);
        long latestAccepted = operationRepository.maxRoomSeq(roomId);
        long latestPublished = outboxRepository.latestPublishedRoomSeq(roomId);
        return new RoomDeliveryRuntimeResponse(
                roomId,
                pending,
                retry,
                published,
                outboxRepository.oldestPendingAgeMs(roomId),
                latestPublished,
                latestAccepted,
                Math.max(0, latestAccepted - latestPublished),
                0,
                deliveryStatus(pending, retry, parked, latestAccepted, latestPublished));
    }

    public RoomDeliveryDrainResponse drain(UUID roomId) {
        RoomDeliveryRuntimeResponse before = status(roomId);
        int attempted = Math.toIntExact(Math.min(drainLimit, before.outboxPendingCount() + before.outboxRetryCount()));
        int published = outboxDispatcher.dispatchRoomOnce(roomId, attempted);
        RoomDeliveryRuntimeResponse after = status(roomId);
        return new RoomDeliveryDrainResponse(
                roomId,
                attempted,
                published,
                0,
                Math.max(0, attempted - published),
                after.outboxPendingCount() + after.outboxRetryCount());
    }

    private String deliveryStatus(long pending, long retry, long parked, long latestAccepted, long latestPublished) {
        if (parked > 0) {
            return "STALLED";
        }
        if (retry > 0) {
            return "RETRYING";
        }
        if (pending > 0 || latestPublished < latestAccepted) {
            return "BACKLOGGED";
        }
        return "HEALTHY";
    }
}
