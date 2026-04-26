package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncforge.api.documentstate.application.DocumentStateService;
import com.syncforge.api.documentstate.model.DocumentLiveState;
import com.syncforge.api.node.NodeIdentity;
import com.syncforge.api.operation.application.OperationService;
import com.syncforge.api.operation.model.OperationRecord;
import com.syncforge.api.operation.model.OperationSubmitResult;
import com.syncforge.api.operation.model.SubmitOperationCommand;
import com.syncforge.api.operation.store.OperationRepository;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {
        "syncforge.redis.stream.enabled=true",
        "syncforge.redis.stream.maxlen=10000",
        "syncforge.rate-limit.operations-per-connection-per-second=100",
        "syncforge.rate-limit.operations-per-user-per-room-per-minute=200"
})
@Import(NoLostOperationInvariantIntegrationTest.StreamPublisherTestConfig.class)
class NoLostOperationInvariantIntegrationTest extends AbstractIntegrationTest {
    private static final AtomicReference<String> FAILING_OPERATION_ID = new AtomicReference<>();

    @Autowired
    OperationService operationService;

    @Autowired
    OperationRepository operationRepository;

    @Autowired
    DocumentStateService documentStateService;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    RoomStreamKeyFactory keyFactory;

    @BeforeEach
    void clearPublisherFailure() {
        FAILING_OPERATION_ID.set(null);
    }

    @Test
    void ackedOperationsAreDurableReplayableAndStreamed() {
        Fixture fixture = fixture();
        redisTemplate.delete(keyFactory.roomStreamKey(fixture.roomId()));

        OperationSubmitResult insert = submit(fixture, "lost-insert", 1, 0, "TEXT_INSERT",
                Map.of("position", 0, "text", "abc"));
        OperationSubmitResult delete = submit(fixture, "lost-delete", 2, 1, "TEXT_DELETE",
                Map.of("position", 1, "length", 1));
        OperationSubmitResult noop = submit(fixture, "lost-noop", 3, 2, "NOOP", Map.of());
        OperationSubmitResult transformed = submit(fixture, "zzzz-lost-transform", 4, 1, "TEXT_INSERT",
                Map.of("position", 1, "text", "Z"));
        OperationSubmitResult duplicate = submit(fixture, "lost-insert", 99, 0, "TEXT_INSERT",
                Map.of("position", 0, "text", "abc"));

        assertAckDurable(fixture, insert, "TEXT_INSERT");
        assertAckDurable(fixture, delete, "TEXT_DELETE");
        assertAckDurable(fixture, noop, "NOOP");
        assertAckDurable(fixture, transformed, "TEXT_INSERT");
        assertThat(duplicate.accepted()).isTrue();
        assertThat(duplicate.duplicate()).isTrue();
        assertThat(duplicate.roomSeq()).isEqualTo(insert.roomSeq());
        assertThat(duplicate.revision()).isEqualTo(insert.revision());

        DocumentLiveState live = documentStateService.getOrInitialize(fixture.roomId());
        assertThat(live.contentText()).isEqualTo("aZc");
        DocumentLiveState replay = documentStateService.rebuildFromOperationLog(fixture.roomId()).state();
        assertThat(replay.contentText()).isEqualTo(live.contentText());
        assertThat(replay.currentRevision()).isEqualTo(live.currentRevision());
        assertThat(replay.currentRoomSeq()).isEqualTo(live.currentRoomSeq());

        assertThat(redisTemplate.opsForStream().range(keyFactory.roomStreamKey(fixture.roomId()), Range.unbounded()))
                .hasSize(4);
    }

    @Test
    void streamPublishFailureRollsBackWithoutAckOrPersistence() {
        Fixture fixture = fixture();
        OperationSubmitResult baseline = submit(fixture, "lost-baseline", 1, 0, "TEXT_INSERT",
                Map.of("position", 0, "text", "ok"));
        DocumentLiveState before = documentStateService.getOrInitialize(fixture.roomId());
        FAILING_OPERATION_ID.set("lost-publish-fails");

        assertThatThrownBy(() -> submit(fixture, "lost-publish-fails", 2, baseline.revision(), "TEXT_INSERT",
                Map.of("position", 2, "text", "!")))
                .isInstanceOf(StreamPublishException.class);

        assertThat(operationRepository.findByRoomAndOperationId(fixture.roomId(), "lost-publish-fails")).isEmpty();
        DocumentLiveState after = documentStateService.getOrInitialize(fixture.roomId());
        assertThat(after.contentText()).isEqualTo(before.contentText());
        assertThat(after.currentRevision()).isEqualTo(before.currentRevision());
        assertThat(after.currentRoomSeq()).isEqualTo(before.currentRoomSeq());
        assertThat(jdbcTemplate.queryForObject("""
                select current_room_seq
                from room_sequence_counters
                where room_id = ?
                """, Long.class, fixture.roomId())).isEqualTo(before.currentRoomSeq());
    }

    private OperationSubmitResult submit(
            Fixture fixture,
            String operationId,
            long clientSeq,
            long baseRevision,
            String operationType,
            Map<String, Object> operation) {
        return operationService.submit(new SubmitOperationCommand(
                fixture.roomId(),
                fixture.editorId(),
                "no-lost-connection",
                "no-lost-session",
                operationId,
                clientSeq,
                baseRevision,
                operationType,
                operation));
    }

    private void assertAckDurable(Fixture fixture, OperationSubmitResult ack, String operationType) {
        assertThat(ack.accepted()).isTrue();
        Optional<OperationRecord> record = operationRepository.findByRoomAndOperationId(fixture.roomId(), ack.operationId());
        assertThat(record).isPresent();
        assertThat(record.get().roomSeq()).isEqualTo(ack.roomSeq());
        assertThat(record.get().resultingRevision()).isEqualTo(ack.revision());
        assertThat(record.get().operationId()).isEqualTo(ack.operationId());
        assertThat(record.get().operationType()).isEqualTo(operationType);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StreamPublisherTestConfig {
        @Bean
        @Primary
        RoomEventStreamPublisher noLostOperationStreamPublisher(
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
