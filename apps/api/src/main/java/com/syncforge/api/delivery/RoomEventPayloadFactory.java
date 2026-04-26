package com.syncforge.api.delivery;

import java.util.LinkedHashMap;
import java.util.Map;

import com.syncforge.api.operation.model.OperationRecord;
import org.springframework.stereotype.Component;

@Component
public class RoomEventPayloadFactory {
    public static final String OPERATION_APPLIED = "OPERATION_APPLIED";

    public Map<String, Object> operationApplied(OperationRecord operation, boolean transformed) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", logicalEventKey(operation.roomId().toString(), operation.roomSeq()));
        payload.put("roomId", operation.roomId().toString());
        payload.put("roomSeq", operation.roomSeq());
        payload.put("revision", operation.resultingRevision());
        payload.put("operationId", operation.operationId());
        payload.put("userId", operation.userId().toString());
        payload.put("clientSeq", operation.clientSeq());
        payload.put("operationType", operation.operationType());
        payload.put("operation", operation.operation());
        payload.put("transformed", transformed);
        payload.put("createdAt", operation.createdAt().toString());
        return payload;
    }

    public String logicalEventKey(String roomId, long roomSeq) {
        return roomId + ":" + roomSeq;
    }
}
