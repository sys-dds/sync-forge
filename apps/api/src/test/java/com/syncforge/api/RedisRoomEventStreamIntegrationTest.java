package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncforge.api.delivery.RoomEventOutboxDispatcher;
import com.syncforge.api.node.NodeIdentity;
import com.syncforge.api.operation.model.OperationRecord;
import com.syncforge.api.stream.application.RoomEventStreamProperties;
import com.syncforge.api.stream.application.RoomEventStreamConsumer;
import com.syncforge.api.stream.application.RoomEventStreamPublisher;
import com.syncforge.api.stream.application.RoomStreamKeyFactory;
import com.syncforge.api.stream.application.StreamPublishException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {
        "syncforge.redis.stream.enabled=true",
        "syncforge.redis.stream.maxlen=10000",
        "syncforge.rate-limit.operations-per-connection-per-second=50",
        "syncforge.rate-limit.operations-per-user-per-room-per-minute=100"
})
class RedisRoomEventStreamIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    RoomStreamKeyFactory keyFactory;

    @Autowired
    RoomEventStreamConsumer streamConsumer;

    @Autowired
    RoomEventOutboxDispatcher outboxDispatcher;

    @Autowired
    NodeIdentity nodeIdentity;

    @Test
    void acceptedOperationPublishesStreamEventWithRequiredPayload() throws Exception {
        Fixture fixture = fixture();
        String streamKey = keyFactory.roomStreamKey(fixture.roomId());
        redisTemplate.delete(streamKey);

        TestSocket editor = TestSocket.connect(websocketUri(), fixture.editorId(), "redis-device", "redis-session", objectMapper);
        join(editor, fixture.roomId().toString());
        editor.drain();
        editor.send(operationMessage("stream-op-1", 1, 0, Map.of("position", 0, "text", "s"), fixture.roomId().toString()));
        assertThat(payload(editor.nextOfType("OPERATION_ACK"))).containsEntry("operationId", "stream-op-1");
        assertThat(outboxDispatcher.dispatchOnce(10)).isEqualTo(1);

        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().range(streamKey, Range.unbounded());
        assertThat(records).hasSize(1);
        Map<Object, Object> value = records.getFirst().getValue();
        assertThat(value)
                .containsEntry("eventId", fixture.roomId() + ":1")
                .containsEntry("roomId", fixture.roomId().toString())
                .containsEntry("roomSeq", "1")
                .containsEntry("revision", "1")
                .containsEntry("operationId", "stream-op-1")
                .containsEntry("userId", fixture.editorId().toString())
                .containsEntry("clientSeq", "1")
                .containsEntry("operationType", "TEXT_INSERT")
                .containsEntry("transformed", "false")
                .containsEntry("producedByNodeId", nodeIdentity.nodeId());
        assertThat(value.get("operation").toString()).contains("\"text\":\"s\"");
        editor.close();
    }

    @Test
    void consumerFansOutStreamEventPersistsOffsetAvoidsDuplicatesAndKeepsRoomIsolation() throws Exception {
        Fixture fixture = fixture();
        Fixture otherFixture = fixture();
        redisTemplate.delete(keyFactory.roomStreamKey(fixture.roomId()));
        redisTemplate.delete(keyFactory.roomStreamKey(otherFixture.roomId()));

        TestSocket editor = TestSocket.connect(websocketUri(), fixture.editorId(), "stream-editor", "stream-editor-session", objectMapper);
        TestSocket listener = TestSocket.connect(websocketUri(), fixture.ownerId(), "stream-listener", "stream-listener-session", objectMapper);
        TestSocket otherRoom = TestSocket.connect(websocketUri(), otherFixture.ownerId(), "other-listener", "other-listener-session", objectMapper);
        join(editor, fixture.roomId().toString());
        join(listener, fixture.roomId().toString());
        join(otherRoom, otherFixture.roomId().toString());
        editor.drain();
        listener.drain();
        otherRoom.drain();

        editor.send(operationMessage("stream-fanout-1", 1, 0, Map.of("position", 0, "text", "f"), fixture.roomId().toString()));
        assertThat(payload(editor.nextOfType("OPERATION_ACK"))).containsEntry("operationId", "stream-fanout-1");
        assertThat(outboxDispatcher.dispatchOnce(10)).isEqualTo(1);
        assertThat(listener.hasMessageOfTypeWithin("OPERATION_APPLIED", 200)).isFalse();

        assertThat(streamConsumer.pollRoom(fixture.roomId())).isEqualTo(1);
        assertThat(payload(listener.nextOfType("OPERATION_APPLIED")))
                .containsEntry("operationId", "stream-fanout-1")
                .containsEntry("roomSeq", 1);
        assertThat(otherRoom.hasMessageOfTypeWithin("OPERATION_APPLIED", 300)).isFalse();
        assertThat(jdbcTemplate.queryForObject("""
                select last_room_seq
                from room_stream_offsets
                where room_id = ? and node_id = ?
                """, Long.class, fixture.roomId(), nodeIdentity.nodeId())).isEqualTo(1L);

        assertThat(streamConsumer.pollRoom(fixture.roomId())).isZero();
        assertThat(listener.hasMessageOfTypeWithin("OPERATION_APPLIED", 300)).isFalse();

        jdbcTemplate.update("""
                update room_stream_offsets
                set last_room_seq = 0, last_stream_id = null
                where room_id = ? and node_id = ?
                """, fixture.roomId(), nodeIdentity.nodeId());
        assertThat(streamConsumer.pollRoom(fixture.roomId())).isEqualTo(1);
        assertThat(listener.hasMessageOfTypeWithin("OPERATION_APPLIED", 300)).isFalse();

        editor.close();
        listener.close();
        otherRoom.close();
    }

    @Test
    void disabledStreamFallbackReturnsCleanlyWithoutPublishing() {
        Fixture fixture = fixture();
        RoomEventStreamProperties disabled = new RoomEventStreamProperties("syncforge:test-disabled:", 10000, 100, false);
        RoomEventStreamPublisher publisher = new RoomEventStreamPublisher(redisTemplate, objectMapper, disabled,
                new RoomStreamKeyFactory(disabled), nodeIdentity);
        OperationRecord record = operationRecord(fixture, "disabled-stream-op");
        assertThat(publisher.publishAcceptedOperation(record, false)).isEmpty();
        assertThat(redisTemplate.hasKey("syncforge:test-disabled:" + fixture.roomId())).isFalse();
    }

    @Test
    void requiredStreamPublishFailureThrowsExplicitException() {
        Fixture fixture = fixture();
        RoomEventStreamProperties enabled = new RoomEventStreamProperties("syncforge:test-failure:", 10000, 100, true);
        RoomEventStreamPublisher publisher = new RoomEventStreamPublisher(new StringRedisTemplate(), objectMapper, enabled,
                new RoomStreamKeyFactory(enabled), nodeIdentity);
        assertThatThrownBy(() -> publisher.publishAcceptedOperation(operationRecord(fixture, "failing-stream-op"), false))
                .isInstanceOf(StreamPublishException.class)
                .hasMessageContaining("Failed to publish accepted room operation to Redis Stream");
    }

    private OperationRecord operationRecord(Fixture fixture, String operationId) {
        return new OperationRecord(
                UUID.randomUUID(),
                fixture.roomId(),
                fixture.editorId(),
                "connection-id",
                operationId,
                "client-session",
                1,
                0,
                1,
                1,
                "TEXT_INSERT",
                Map.of("position", 0, "text", "x"),
                nodeIdentity.nodeId(),
                1L,
                OffsetDateTime.now());
    }

    private void join(TestSocket socket, String roomId) throws Exception {
        socket.send(Map.of("type", "JOIN_ROOM", "messageId", "join-" + roomId, "roomId", roomId, "payload", Map.of()));
        socket.nextOfType("JOINED_ROOM");
    }

    private Map<String, Object> operationMessage(
            String operationId,
            long clientSeq,
            long baseRevision,
            Map<String, Object> operation,
            String roomId) {
        return Map.of(
                "type", "SUBMIT_OPERATION",
                "messageId", operationId + "-message",
                "roomId", roomId,
                "payload", Map.of(
                        "operationId", operationId,
                        "clientSeq", clientSeq,
                        "baseRevision", baseRevision,
                        "operationType", "TEXT_INSERT",
                        "operation", operation));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("payload");
    }
}
