package com.syncforge.api.stream.application;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncforge.api.node.NodeIdentity;
import com.syncforge.api.operation.model.OperationRecord;
import com.syncforge.api.stream.model.RoomStreamEvent;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class RoomEventStreamPublisher {
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final RoomEventStreamProperties properties;
    private final RoomStreamKeyFactory keyFactory;
    private final NodeIdentity nodeIdentity;

    public RoomEventStreamPublisher(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            RoomEventStreamProperties properties,
            RoomStreamKeyFactory keyFactory,
            NodeIdentity nodeIdentity) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.keyFactory = keyFactory;
        this.nodeIdentity = nodeIdentity;
    }

    public Optional<RoomStreamEvent> publishAcceptedOperation(OperationRecord operation, boolean transformed) {
        RoomStreamEvent event = new RoomStreamEvent(
                "%s:%d".formatted(operation.roomId(), operation.roomSeq()),
                operation.roomId(),
                operation.roomSeq(),
                operation.resultingRevision(),
                operation.operationId(),
                operation.userId(),
                operation.clientSeq(),
                operation.operationType(),
                operation.operation(),
                transformed,
                nodeIdentity.nodeId(),
                operation.createdAt());
        if (!properties.enabled()) {
            return Optional.empty();
        }
        try {
            Map<String, String> fields = toFields(event);
            String streamKey = keyFactory.roomStreamKey(operation.roomId());
            RecordId recordId = redisTemplate.opsForStream().add(streamKey, fields);
            redisTemplate.opsForStream().trim(streamKey, properties.maxLen(), true);
            if (recordId == null) {
                throw new StreamPublishException("Redis Stream publish returned no record id", null);
            }
            return Optional.of(event);
        } catch (RuntimeException | JsonProcessingException exception) {
            throw new StreamPublishException("Failed to publish accepted room operation to Redis Stream", exception);
        }
    }

    private Map<String, String> toFields(RoomStreamEvent event) throws JsonProcessingException {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("eventId", event.eventId());
        fields.put("roomId", event.roomId().toString());
        fields.put("roomSeq", Long.toString(event.roomSeq()));
        fields.put("revision", Long.toString(event.revision()));
        fields.put("operationId", event.operationId());
        fields.put("userId", event.userId().toString());
        fields.put("clientSeq", Long.toString(event.clientSeq()));
        fields.put("operationType", event.operationType());
        fields.put("operation", objectMapper.writeValueAsString(event.operation()));
        fields.put("transformed", Boolean.toString(event.transformed()));
        fields.put("producedByNodeId", event.producedByNodeId());
        fields.put("createdAt", event.createdAt().toString());
        return fields;
    }
}
