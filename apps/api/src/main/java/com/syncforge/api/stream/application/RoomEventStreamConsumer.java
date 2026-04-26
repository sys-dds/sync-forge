package com.syncforge.api.stream.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncforge.api.node.NodeIdentity;
import com.syncforge.api.stream.model.NodeRoomSubscription;
import com.syncforge.api.stream.model.RoomStreamOffset;
import com.syncforge.api.stream.store.RoomStreamOffsetRepository;
import com.syncforge.api.websocket.RoomWebSocketBroadcaster;
import com.syncforge.api.websocket.RoomWebSocketEnvelope;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class RoomEventStreamConsumer {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final RoomEventStreamProperties properties;
    private final RoomStreamKeyFactory keyFactory;
    private final RoomStreamOffsetRepository offsetRepository;
    private final NodeRoomSubscriptionService subscriptionService;
    private final RoomWebSocketBroadcaster broadcaster;
    private final NodeIdentity nodeIdentity;

    public RoomEventStreamConsumer(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            RoomEventStreamProperties properties,
            RoomStreamKeyFactory keyFactory,
            RoomStreamOffsetRepository offsetRepository,
            NodeRoomSubscriptionService subscriptionService,
            RoomWebSocketBroadcaster broadcaster,
            NodeIdentity nodeIdentity) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.keyFactory = keyFactory;
        this.offsetRepository = offsetRepository;
        this.subscriptionService = subscriptionService;
        this.broadcaster = broadcaster;
        this.nodeIdentity = nodeIdentity;
    }

    public int pollSubscribedRooms() {
        if (!properties.enabled()) {
            return 0;
        }
        int delivered = 0;
        for (NodeRoomSubscription subscription : subscriptionService.activeSubscriptions()) {
            delivered += pollRoom(subscription.roomId());
        }
        return delivered;
    }

    public int pollRoom(UUID roomId) {
        if (!properties.enabled()) {
            return 0;
        }
        String streamKey = keyFactory.roomStreamKey(roomId);
        RoomStreamOffset offset = offsetRepository.ensure(roomId, nodeIdentity.nodeId(), streamKey);
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().range(streamKey, Range.unbounded());
        if (records == null || records.isEmpty()) {
            return 0;
        }
        int delivered = 0;
        long lastRoomSeq = offset.lastRoomSeq();
        for (MapRecord<String, Object, Object> record : records) {
            Map<Object, Object> value = record.getValue();
            UUID eventRoomId = UUID.fromString(field(value, "roomId"));
            if (!roomId.equals(eventRoomId)) {
                continue;
            }
            long roomSeq = Long.parseLong(field(value, "roomSeq"));
            if (roomSeq <= lastRoomSeq) {
                continue;
            }
            if (roomSeq > lastRoomSeq + 1) {
                offsetRepository.markGap(roomId, nodeIdentity.nodeId(), streamKey, lastRoomSeq + 1, roomSeq,
                        "Expected roomSeq " + (lastRoomSeq + 1) + " but observed " + roomSeq);
                break;
            }
            broadcaster.broadcast(roomId, toEnvelope(value));
            String recordId = record.getId().getValue();
            offsetRepository.update(roomId, nodeIdentity.nodeId(), streamKey, recordId, roomSeq);
            subscriptionService.markEvent(roomId);
            lastRoomSeq = roomSeq;
            delivered++;
        }
        return delivered;
    }

    private RoomWebSocketEnvelope toEnvelope(Map<Object, Object> value) {
        UUID roomId = UUID.fromString(field(value, "roomId"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", field(value, "eventId"));
        payload.put("operationId", field(value, "operationId"));
        payload.put("userId", field(value, "userId"));
        payload.put("clientSeq", Long.parseLong(field(value, "clientSeq")));
        payload.put("roomSeq", Long.parseLong(field(value, "roomSeq")));
        payload.put("revision", Long.parseLong(field(value, "revision")));
        payload.put("operationType", field(value, "operationType"));
        payload.put("operation", operation(field(value, "operation")));
        payload.put("transformed", Boolean.parseBoolean(field(value, "transformed")));
        payload.put("producedByNodeId", field(value, "producedByNodeId"));
        return new RoomWebSocketEnvelope("OPERATION_APPLIED", null, roomId.toString(), null, payload);
    }

    private Map<String, Object> operation(String raw) {
        try {
            return objectMapper.readValue(raw, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new StreamPublishException("Failed to decode Redis Stream operation payload", exception);
        }
    }

    private String field(Map<Object, Object> value, String name) {
        Object field = value.get(name);
        if (field == null) {
            throw new StreamPublishException("Redis Stream event is missing field " + name, null);
        }
        return field.toString();
    }
}
