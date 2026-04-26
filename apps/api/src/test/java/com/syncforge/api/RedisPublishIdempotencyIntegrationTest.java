package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncforge.api.delivery.RoomEventOutboxDispatcher;
import com.syncforge.api.delivery.RoomEventOutboxRecord;
import com.syncforge.api.delivery.RoomEventOutboxRepository;
import com.syncforge.api.delivery.RoomEventOutboxStatus;
import com.syncforge.api.operation.application.OperationService;
import com.syncforge.api.operation.model.OperationSubmitResult;
import com.syncforge.api.operation.model.SubmitOperationCommand;
import com.syncforge.api.stream.application.RoomEventStreamConsumer;
import com.syncforge.api.stream.application.RoomStreamKeyFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "syncforge.redis.stream.enabled=true")
class RedisPublishIdempotencyIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    OperationService operationService;

    @Autowired
    RoomEventOutboxRepository outboxRepository;

    @Autowired
    RoomEventOutboxDispatcher dispatcher;

    @Autowired
    RoomEventStreamConsumer streamConsumer;

    @Autowired
    RoomStreamKeyFactory keyFactory;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Test
    void duplicatePhysicalStreamRecordDoesNotDuplicateLogicalFanout() throws Exception {
        Fixture fixture = fixture();
        TestSocket listener = TestSocket.connect(websocketUri(), fixture.viewerId(), "idempotent-device",
                "idempotent-session", objectMapper);
        join(listener, fixture.roomId().toString());
        listener.drain();

        OperationSubmitResult accepted = submit(fixture, "idempotent-op-1", 1, 0);
        assertThat(dispatcher.dispatchOnce(10)).isEqualTo(1);

        String streamKey = keyFactory.roomStreamKey(fixture.roomId());
        MapRecord<String, Object, Object> original = redisTemplate.opsForStream()
                .range(streamKey, Range.unbounded())
                .getFirst();
        redisTemplate.opsForStream().add(streamKey, original.getValue());

        assertThat(streamConsumer.pollRoom(fixture.roomId())).isEqualTo(1);
        Map<String, Object> delivered = payload(listener.nextOfType("OPERATION_APPLIED"));
        assertThat(delivered).containsEntry("eventId", fixture.roomId() + ":" + accepted.roomSeq());
        assertThat(((Number) delivered.get("roomSeq")).longValue()).isEqualTo(accepted.roomSeq());
        assertThat(streamConsumer.pollRoom(fixture.roomId())).isZero();
        assertThat(listener.hasMessageOfTypeWithin("OPERATION_APPLIED", 250)).isFalse();

        listener.close();
    }

    @Test
    void sameLogicalEventKeyAndRoomSeqStayIdempotent() {
        Fixture fixture = fixture();
        OperationSubmitResult accepted = submit(fixture, "idempotent-op-2", 1, 0);
        RoomEventOutboxRecord original = outboxRepository.findByRoomSeq(fixture.roomId(), accepted.roomSeq()).orElseThrow();

        RoomEventOutboxRecord duplicate = outboxRepository.insertPendingOperationEvent(
                UUID.randomUUID(),
                fixture.roomId(),
                original.roomSeq(),
                original.revision(),
                original.operationId(),
                original.logicalEventKey(),
                original.payload());

        assertThat(duplicate.id()).isEqualTo(original.id());
        assertThat(countOutboxRows(fixture.roomId(), accepted.roomSeq())).isEqualTo(1);
        assertThat(outboxRepository.countByStatus(RoomEventOutboxStatus.PENDING)).isEqualTo(1);
    }

    private OperationSubmitResult submit(Fixture fixture, String operationId, long clientSeq, long baseRevision) {
        return operationService.submit(new SubmitOperationCommand(
                fixture.roomId(),
                fixture.editorId(),
                "idempotent-connection",
                "idempotent-session",
                operationId,
                clientSeq,
                baseRevision,
                "TEXT_INSERT",
                Map.of("position", (int) baseRevision, "text", "x")));
    }

    private void join(TestSocket socket, String roomId) throws Exception {
        socket.send(Map.of("type", "JOIN_ROOM", "messageId", "join", "roomId", roomId, "payload", Map.of()));
        socket.nextOfType("JOINED_ROOM");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("payload");
    }

    private int countOutboxRows(UUID roomId, long roomSeq) {
        return jdbcTemplate.queryForObject("""
                select count(*)
                from room_event_outbox
                where room_id = ? and room_seq = ?
                """, Integer.class, roomId, roomSeq);
    }
}
