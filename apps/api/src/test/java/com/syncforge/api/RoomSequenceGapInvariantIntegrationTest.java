package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncforge.api.backpressure.application.BackpressureService;
import com.syncforge.api.node.NodeIdentity;
import com.syncforge.api.operation.application.OperationService;
import com.syncforge.api.operation.model.OperationRecord;
import com.syncforge.api.operation.model.OperationSubmitResult;
import com.syncforge.api.operation.model.SubmitOperationCommand;
import com.syncforge.api.stream.application.RoomEventStreamProperties;
import com.syncforge.api.stream.application.RoomEventStreamPublisher;
import com.syncforge.api.stream.application.RoomStreamKeyFactory;
import com.syncforge.api.stream.application.StreamPublishException;
import com.syncforge.api.stream.model.RoomStreamEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {
        "syncforge.redis.stream.enabled=true",
        "syncforge.redis.stream.maxlen=10000",
        "syncforge.rate-limit.operations-per-connection-per-second=2",
        "syncforge.rate-limit.operations-per-user-per-room-per-minute=100",
        "syncforge.backpressure.warning-pending-events=100",
        "syncforge.backpressure.max-room-pending-events=100"
})
@Import(RoomSequenceGapInvariantIntegrationTest.StreamPublisherTestConfig.class)
class RoomSequenceGapInvariantIntegrationTest extends AbstractIntegrationTest {
    private static final AtomicReference<String> FAILING_OPERATION_ID = new AtomicReference<>();

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    OperationService operationService;

    @Autowired
    BackpressureService backpressureService;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    RoomStreamKeyFactory keyFactory;

    @BeforeEach
    void clearPublisherFailure() {
        FAILING_OPERATION_ID.set(null);
    }

    @Test
    void acceptedRoomSequencesStayMonotonicGaplessAcrossDirectRejectsDuplicatesTransformsAndRedisFailure() {
        Fixture fixture = fixture();
        redisTemplate.delete(keyFactory.roomStreamKey(fixture.roomId()));

        assertThat(submit(fixture, fixture.editorId(), "seq-1", 1, 0, "TEXT_INSERT",
                Map.of("position", 0, "text", "a")).accepted()).isTrue();
        assertThat(submit(fixture, fixture.editorId(), "seq-2", 2, 1, "TEXT_INSERT",
                Map.of("position", 1, "text", "b")).accepted()).isTrue();
        assertThat(submit(fixture, fixture.editorId(), "seq-3", 3, 2, "TEXT_INSERT",
                Map.of("position", 2, "text", "c")).accepted()).isTrue();
        assertGapless(fixture.roomId());

        long beforeRejects = maxSeq(fixture.roomId());
        assertThat(submit(fixture, fixture.editorId(), "seq-invalid-client", 0, 3, "TEXT_INSERT",
                Map.of("position", 3, "text", "x")).accepted()).isFalse();
        assertThat(submit(fixture, fixture.editorId(), "seq-invalid-payload", 4, 3, "TEXT_INSERT",
                Map.of("position", 99, "text", "x")).accepted()).isFalse();
        assertThat(submit(fixture, fixture.viewerId(), "seq-viewer-reject", 1, 3, "TEXT_INSERT",
                Map.of("position", 3, "text", "x")).accepted()).isFalse();
        assertThat(maxSeq(fixture.roomId())).isEqualTo(beforeRejects);

        OperationSubmitResult duplicate = submit(fixture, fixture.editorId(), "seq-2", 99, 1, "TEXT_INSERT",
                Map.of("position", 1, "text", "b"));
        assertThat(duplicate.accepted()).isTrue();
        assertThat(duplicate.duplicate()).isTrue();
        assertThat(maxSeq(fixture.roomId())).isEqualTo(beforeRejects);

        OperationSubmitResult transformed = submit(fixture, fixture.editorId(), "zzzz-seq-transform", 5, 1, "TEXT_INSERT",
                Map.of("position", 1, "text", "Z"));
        assertThat(transformed.accepted()).isTrue();
        assertThat(transformed.roomSeq()).isEqualTo(4);

        FAILING_OPERATION_ID.set("seq-stream-fails");
        assertThatThrownBy(() -> submit(fixture, fixture.editorId(), "seq-stream-fails", 6, 4, "TEXT_INSERT",
                Map.of("position", 4, "text", "!")))
                .isInstanceOf(StreamPublishException.class);
        assertThat(maxSeq(fixture.roomId())).isEqualTo(4);
        assertGapless(fixture.roomId());
        assertThat(counterSeq(fixture.roomId())).isEqualTo(maxSeq(fixture.roomId()));

        List<MapRecord<String, Object, Object>> stream = redisTemplate.opsForStream()
                .range(keyFactory.roomStreamKey(fixture.roomId()), Range.unbounded());
        assertThat(stream).hasSize(4);
        assertThat(stream).extracting(record -> record.getValue().get("roomSeq"))
                .containsExactly("1", "2", "3", "4");
    }

    @Test
    void websocketRateLimitAndBackpressureRejectsDoNotConsumeRoomSeq() throws Exception {
        Fixture rateFixture = fixture();
        TestSocket rateSocket = TestSocket.connect(websocketUri(), rateFixture.editorId(), "rate-device", "rate-session",
                objectMapper);
        join(rateSocket, rateFixture.roomId().toString());
        rateSocket.drain();
        rateSocket.send(operationMessage("seq-rate-1", 1, 0, Map.of("position", 0, "text", "a"), rateFixture.roomId().toString()));
        assertThat(payload(rateSocket.nextOfType("OPERATION_ACK"))).containsEntry("operationId", "seq-rate-1");
        rateSocket.send(operationMessage("seq-rate-2", 2, 1, Map.of("position", 1, "text", "b"), rateFixture.roomId().toString()));
        assertThat(payload(rateSocket.nextOfType("OPERATION_ACK"))).containsEntry("operationId", "seq-rate-2");
        rateSocket.send(operationMessage("seq-rate-3", 3, 2, Map.of("position", 2, "text", "c"), rateFixture.roomId().toString()));
        assertThat(payload(rateSocket.nextOfType("RATE_LIMITED"))).containsEntry("code", "OPERATION_RATE_LIMITED");
        assertThat(maxSeq(rateFixture.roomId())).isEqualTo(2);
        rateSocket.close();

        Fixture pressureFixture = fixture();
        for (int i = 0; i < 100; i++) {
            backpressureService.recordAcceptedRoomEvent(pressureFixture.roomId());
        }
        TestSocket pressureSocket = TestSocket.connect(websocketUri(), pressureFixture.editorId(), "pressure-device",
                "pressure-session", objectMapper);
        join(pressureSocket, pressureFixture.roomId().toString());
        pressureSocket.drain();
        pressureSocket.send(operationMessage("seq-pressure-reject", 1, 0, Map.of("position", 0, "text", "p"),
                pressureFixture.roomId().toString()));
        assertThat(payload(pressureSocket.nextOfType("OPERATION_NACK"))).containsEntry("code", "ROOM_BACKPRESSURE");
        assertThat(maxSeq(pressureFixture.roomId())).isZero();
        pressureSocket.close();
    }

    private OperationSubmitResult submit(
            Fixture fixture,
            UUID userId,
            String operationId,
            long clientSeq,
            long baseRevision,
            String operationType,
            Map<String, Object> operation) {
        return operationService.submit(new SubmitOperationCommand(
                fixture.roomId(),
                userId,
                "sequence-connection",
                "sequence-session",
                operationId,
                clientSeq,
                baseRevision,
                operationType,
                operation));
    }

    private void assertGapless(UUID roomId) {
        List<Long> sequences = jdbcTemplate.queryForList("""
                select room_seq
                from room_operations
                where room_id = ?
                order by room_seq
                """, Long.class, roomId);
        assertThat(sequences).containsExactlyElementsOf(
                java.util.stream.LongStream.rangeClosed(1, sequences.size()).boxed().toList());
    }

    private long maxSeq(UUID roomId) {
        return jdbcTemplate.queryForObject("""
                select coalesce(max(room_seq), 0)
                from room_operations
                where room_id = ?
                """, Long.class, roomId);
    }

    private long counterSeq(UUID roomId) {
        return jdbcTemplate.queryForObject("""
                select current_room_seq
                from room_sequence_counters
                where room_id = ?
                """, Long.class, roomId);
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

    @TestConfiguration(proxyBeanMethods = false)
    static class StreamPublisherTestConfig {
        @Bean
        @Primary
        RoomEventStreamPublisher sequenceInvariantStreamPublisher(
                StringRedisTemplate redisTemplate,
                ObjectMapper objectMapper,
                RoomEventStreamProperties properties,
                RoomStreamKeyFactory keyFactory,
                NodeIdentity nodeIdentity) {
            return new RoomEventStreamPublisher(redisTemplate, objectMapper, properties, keyFactory, nodeIdentity) {
                @Override
                public Optional<RoomStreamEvent> publishAcceptedOperation(OperationRecord operation, boolean transformed) {
                    if (operation.operationId().equals(FAILING_OPERATION_ID.get())) {
                        throw new StreamPublishException("forced stream failure", new RuntimeException("forced"));
                    }
                    return super.publishAcceptedOperation(operation, transformed);
                }
            };
        }
    }
}
