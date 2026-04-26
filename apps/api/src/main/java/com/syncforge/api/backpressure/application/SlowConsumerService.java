package com.syncforge.api.backpressure.application;

import java.util.List;
import java.util.UUID;

import com.syncforge.api.backpressure.model.SlowConsumerEvent;
import com.syncforge.api.backpressure.store.SlowConsumerRepository;
import org.springframework.stereotype.Service;

@Service
public class SlowConsumerService {
    private final SlowConsumerRepository repository;

    public SlowConsumerService(SlowConsumerRepository repository) {
        this.repository = repository;
    }

    public SlowConsumerEvent warn(UUID roomId, UUID userId, String connectionId, String nodeId, int queuedMessages, int threshold) {
        return repository.record(roomId, userId, connectionId, nodeId, queuedMessages, threshold, "WARNED", "outbound queue lag exceeded warning threshold");
    }

    public SlowConsumerEvent quarantined(UUID roomId, UUID userId, String connectionId, String nodeId, int queuedMessages, int threshold) {
        return repository.record(roomId, userId, connectionId, nodeId, queuedMessages, threshold, "QUARANTINED", "outbound queue lag exceeded quarantine threshold");
    }

    public List<SlowConsumerEvent> listByRoom(UUID roomId) {
        return repository.listByRoom(roomId);
    }
}
