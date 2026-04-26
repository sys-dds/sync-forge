package com.syncforge.api.resume.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.syncforge.api.documentstate.application.DocumentStateService;
import com.syncforge.api.documentstate.model.DocumentLiveState;
import com.syncforge.api.operation.model.OperationRecord;
import com.syncforge.api.operation.store.OperationRepository;
import com.syncforge.api.resume.model.BackfillResult;
import com.syncforge.api.resume.store.RoomBackfillRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class RoomBackfillService {
    private final OperationRepository operationRepository;
    private final RoomBackfillRepository roomBackfillRepository;
    private final DocumentStateService documentStateService;
    private final long maxBackfillEvents;

    public RoomBackfillService(
            OperationRepository operationRepository,
            RoomBackfillRepository roomBackfillRepository,
            DocumentStateService documentStateService,
            @Value("${syncforge.resume.max-backfill-events:100}") long maxBackfillEvents) {
        this.operationRepository = operationRepository;
        this.roomBackfillRepository = roomBackfillRepository;
        this.documentStateService = documentStateService;
        this.maxBackfillEvents = maxBackfillEvents;
    }

    public BackfillResult backfill(UUID roomId, UUID userId, String clientSessionId, long lastSeenRoomSeq) {
        List<OperationRecord> operations = operationRepository.findByRoomAfterRoomSeq(roomId, lastSeenRoomSeq);
        long currentRoomSeq = operations.stream().mapToLong(OperationRecord::roomSeq).max().orElse(lastSeenRoomSeq);
        if (operations.size() > maxBackfillEvents) {
            DocumentLiveState state = documentStateService.getOrInitialize(roomId);
            roomBackfillRepository.record(roomId, userId, clientSessionId, lastSeenRoomSeq, state.currentRoomSeq(),
                    "RESYNC_REQUIRED", 0, "client is too far behind");
            return new BackfillResult("RESYNC_REQUIRED", lastSeenRoomSeq, state.currentRoomSeq(), List.of(), state,
                    "client is too far behind");
        }
        List<Map<String, Object>> events = operations.stream().map(this::eventPayload).toList();
        roomBackfillRepository.record(roomId, userId, clientSessionId, lastSeenRoomSeq, currentRoomSeq,
                "BACKFILLED", events.size(), null);
        return new BackfillResult("BACKFILLED", lastSeenRoomSeq, currentRoomSeq, events, null, null);
    }

    private Map<String, Object> eventPayload(OperationRecord operation) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("operationId", operation.operationId());
        payload.put("userId", operation.userId().toString());
        payload.put("clientSeq", operation.clientSeq());
        payload.put("roomSeq", operation.roomSeq());
        payload.put("revision", operation.resultingRevision());
        payload.put("operationType", operation.operationType());
        payload.put("operation", operation.operation());
        return payload;
    }
}
