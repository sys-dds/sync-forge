package com.syncforge.api.backpressure.application;

import java.util.UUID;

import com.syncforge.api.backpressure.model.RoomBackpressureState;
import com.syncforge.api.backpressure.store.BackpressureRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class BackpressureService {
    private final BackpressureRepository repository;
    private final int maxRoomPendingEvents;
    private final int warningPendingEvents;

    public BackpressureService(
            BackpressureRepository repository,
            @Value("${syncforge.backpressure.max-room-pending-events:1000}") int maxRoomPendingEvents,
            @Value("${syncforge.backpressure.warning-pending-events:800}") int warningPendingEvents) {
        this.repository = repository;
        this.maxRoomPendingEvents = maxRoomPendingEvents;
        this.warningPendingEvents = warningPendingEvents;
    }

    public RoomBackpressureState current(UUID roomId) {
        return repository.find(roomId).orElseGet(() -> repository.ensure(roomId, maxRoomPendingEvents));
    }

    public boolean shouldRejectNewOperation(UUID roomId) {
        return current(roomId).rejecting();
    }

    public RoomBackpressureState recordAcceptedRoomEvent(UUID roomId) {
        return repository.increment(roomId, warningPendingEvents, maxRoomPendingEvents);
    }

    public RoomBackpressureState acknowledgeRoomEvent(UUID roomId) {
        return repository.decrement(roomId, warningPendingEvents, maxRoomPendingEvents);
    }
}
