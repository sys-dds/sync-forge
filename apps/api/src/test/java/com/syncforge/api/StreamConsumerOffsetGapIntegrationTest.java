package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncforge.api.node.NodeIdentity;
import com.syncforge.api.stream.application.RoomEventStreamConsumer;
import com.syncforge.api.stream.application.RoomStreamKeyFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "syncforge.redis.stream.enabled=true")
class StreamConsumerOffsetGapIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    RoomStreamKeyFactory keyFactory;

    @Autowired
    RoomEventStreamConsumer streamConsumer;

    @Autowired
    NodeIdentity nodeIdentity;

    @Test
    void duplicateRestartAndWrongRoomEventsDoNotAdvanceOrLeak() throws Exception {
        Fixture fixture = fixture();
        Fixture other = fixture();
        String streamKey = keyFactory.roomStreamKey(fixture.roomId());
        redisTemplate.delete(streamKey);

        TestSocket listener = TestSocket.connect(websocketUri(), fixture.ownerId(), "consumer-device", "consumer-session",
                objectMapper);
        join(listener, fixture.roomId().toString());
        listener.drain();

        redisTemplate.opsForStream().add(streamKey, event(other.roomId(), 99, "wrong-room-event"));
        redisTemplate.opsForStream().add(streamKey, event(fixture.roomId(), 1, "consumer-event-1"));
        redisTemplate.opsForStream().add(streamKey, event(fixture.roomId(), 1, "consumer-event-1"));

        assertThat(streamConsumer.pollRoom(fixture.roomId())).isEqualTo(1);
        assertThat(payload(listener.nextOfType("OPERATION_APPLIED")))
                .containsEntry("operationId", "consumer-event-1")
                .containsEntry("roomSeq", 1);
        assertThat(listener.hasMessageOfTypeWithin("OPERATION_APPLIED", 250)).isFalse();
        assertOffset(fixture.roomId(), 1, "NORMAL", null, null);

        assertThat(streamConsumer.pollRoom(fixture.roomId())).isZero();
        assertThat(listener.hasMessageOfTypeWithin("OPERATION_APPLIED", 250)).isFalse();
        listener.close();
    }

    @Test
    void roomSeqGapIsDetectedWithoutAdvancingOffsetOrDeliveringOutOfOrder() throws Exception {
        Fixture fixture = fixture();
        String streamKey = keyFactory.roomStreamKey(fixture.roomId());
        redisTemplate.delete(streamKey);

        TestSocket listener = TestSocket.connect(websocketUri(), fixture.ownerId(), "gap-device", "gap-session",
                objectMapper);
        join(listener, fixture.roomId().toString());
        listener.drain();

        redisTemplate.opsForStream().add(streamKey, event(fixture.roomId(), 2, "gap-event-2"));

        assertThat(streamConsumer.pollRoom(fixture.roomId())).isZero();
        assertThat(listener.hasMessageOfTypeWithin("OPERATION_APPLIED", 250)).isFalse();
        assertOffset(fixture.roomId(), 0, "GAP_DETECTED", 1L, 2L);
        listener.close();
    }

    private Map<String, String> event(UUID roomId, long roomSeq, String operationId) {
        Map<String, String> event = new LinkedHashMap<>();
        event.put("eventId", roomId + ":" + roomSeq);
        event.put("roomId", roomId.toString());
        event.put("roomSeq", Long.toString(roomSeq));
        event.put("revision", Long.toString(roomSeq));
        event.put("operationId", operationId);
        event.put("userId", UUID.randomUUID().toString());
        event.put("clientSeq", Long.toString(roomSeq));
        event.put("operationType", "TEXT_INSERT");
        event.put("operation", "{\"position\":0,\"text\":\"x\"}");
        event.put("transformed", "false");
        event.put("producedByNodeId", "test-node");
        event.put("createdAt", "2026-04-26T00:00:00Z");
        return event;
    }

    private void join(TestSocket socket, String roomId) throws Exception {
        socket.send(Map.of("type", "JOIN_ROOM", "messageId", "join-" + roomId, "roomId", roomId, "payload", Map.of()));
        socket.nextOfType("JOINED_ROOM");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("payload");
    }

    private void assertOffset(UUID roomId, long lastRoomSeq, String status, Long expected, Long observed) {
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                select last_room_seq, status, expected_room_seq, observed_room_seq
                from room_stream_offsets
                where room_id = ? and node_id = ?
                """, roomId, nodeIdentity.nodeId());
        assertThat(row.get("last_room_seq")).isEqualTo(lastRoomSeq);
        assertThat(row.get("status")).isEqualTo(status);
        assertThat(row.get("expected_room_seq")).isEqualTo(expected);
        assertThat(row.get("observed_room_seq")).isEqualTo(observed);
    }
}
