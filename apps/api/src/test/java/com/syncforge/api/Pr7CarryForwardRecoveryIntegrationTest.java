package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncforge.api.delivery.PublishedRoomEvent;
import com.syncforge.api.delivery.RoomEventOutboxDispatcher;
import com.syncforge.api.delivery.RoomEventOutboxRecord;
import com.syncforge.api.delivery.RoomEventOutboxRepository;
import com.syncforge.api.delivery.RoomEventOutboxStatus;
import com.syncforge.api.documentstate.application.DocumentStateService;
import com.syncforge.api.node.NodeIdentity;
import com.syncforge.api.operation.application.OperationService;
import com.syncforge.api.operation.model.OperationSubmitResult;
import com.syncforge.api.operation.model.SubmitOperationCommand;
import com.syncforge.api.operation.store.OperationRepository;
import com.syncforge.api.resume.application.RoomBackfillService;
import com.syncforge.api.resume.model.BackfillResult;
import com.syncforge.api.stream.application.RoomEventStreamConsumer;
import com.syncforge.api.stream.application.RoomEventStreamProperties;
import com.syncforge.api.stream.application.RoomEventStreamPublisher;
import com.syncforge.api.stream.application.RoomStreamKeyFactory;
import com.syncforge.api.stream.application.StreamPublishException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {
        "syncforge.redis.stream.enabled=true",
        "syncforge.redis.stream.maxlen=10000"
})
class Pr7CarryForwardRecoveryIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    OperationService operationService;

    @Autowired
    OperationRepository operationRepository;

    @Autowired
    DocumentStateService documentStateService;

    @Autowired
    RoomEventOutboxRepository outboxRepository;

    @Autowired
    RoomEventStreamProperties streamProperties;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    RoomStreamKeyFactory keyFactory;

    @Autowired
    NodeIdentity nodeIdentity;

    @Autowired
    RoomEventStreamConsumer streamConsumer;

    @Autowired
    RoomBackfillService roomBackfillService;

    @Test
    void trueRedisPublishFailureRetriesPublishesDeliversOnceAndBackfillsDbTruth() throws Exception {
        Fixture fixture = fixture();
        String streamKey = keyFactory.roomStreamKey(fixture.roomId());
        redisTemplate.delete(streamKey);
        TestSocket listener = TestSocket.connect(websocketUri(), fixture.viewerId(), "pr7-device", "pr7-session", objectMapper);
        join(listener, fixture.roomId().toString());
        listener.drain();

        OperationSubmitResult accepted = operationService.submit(new SubmitOperationCommand(
                fixture.roomId(),
                fixture.editorId(),
                "pr7-connection",
                "pr7-editor-session",
                "pr7-recovery-op",
                1L,
                0L,
                "TEXT_INSERT",
                Map.of("position", 0, "text", "r")));

        assertThat(accepted.accepted()).isTrue();
        assertThat(operationRepository.findByRoomAndOperationId(fixture.roomId(), "pr7-recovery-op")).isPresent();
        assertThat(documentStateService.getOrInitialize(fixture.roomId()).contentText()).isEqualTo("r");

        BackfillResult beforePublish = roomBackfillService.backfill(fixture.roomId(), fixture.viewerId(), "pr7-missed", 0);
        assertThat(beforePublish.events()).extracting(event -> event.get("operationId")).containsExactly("pr7-recovery-op");

        FailingThenRecoveringPublisher publisher = new FailingThenRecoveringPublisher(
                redisTemplate,
                objectMapper,
                streamProperties,
                keyFactory,
                nodeIdentity);
        RoomEventOutboxDispatcher recoveryDispatcher = new RoomEventOutboxDispatcher(
                outboxRepository,
                publisher,
                streamProperties,
                nodeIdentity,
                30000);

        assertThat(recoveryDispatcher.dispatchOnce(10)).isZero();
        RoomEventOutboxRecord retry = outboxRepository.findByRoomSeq(fixture.roomId(), accepted.roomSeq()).orElseThrow();
        assertThat(retry.status()).isEqualTo(RoomEventOutboxStatus.RETRY);
        assertThat(retry.attemptCount()).isEqualTo(1);
        assertThat(retry.lastError()).contains("simulated Redis publish failure");
        assertThat(operationRepository.findByRoomAndOperationId(fixture.roomId(), "pr7-recovery-op")).isPresent();
        assertThat(documentStateService.getOrInitialize(fixture.roomId()).contentText()).isEqualTo("r");

        jdbcTemplate.update("update room_event_outbox set next_attempt_at = now() where id = ?", retry.id());
        publisher.recover();

        assertThat(recoveryDispatcher.dispatchOnce(10)).isEqualTo(1);
        RoomEventOutboxRecord published = outboxRepository.findByRoomSeq(fixture.roomId(), accepted.roomSeq()).orElseThrow();
        assertThat(published.status()).isEqualTo(RoomEventOutboxStatus.PUBLISHED);
        assertThat(published.publishedStreamKey()).isEqualTo(streamKey);
        assertThat(published.publishedStreamId()).isNotBlank();

        assertThat(streamConsumer.pollRoom(fixture.roomId())).isEqualTo(1);
        assertThat(payload(listener.nextOfType("OPERATION_APPLIED"))).containsEntry("operationId", "pr7-recovery-op");
        assertThat(streamConsumer.pollRoom(fixture.roomId())).isZero();
        assertThat(listener.hasMessageOfTypeWithin("OPERATION_APPLIED", 250)).isFalse();

        BackfillResult afterPublish = roomBackfillService.backfill(fixture.roomId(), fixture.viewerId(), "pr7-after", 0);
        assertThat(afterPublish.events()).extracting(event -> event.get("operationId")).containsExactly("pr7-recovery-op");
        listener.close();
    }

    private void join(TestSocket socket, String roomId) throws Exception {
        socket.send(Map.of("type", "JOIN_ROOM", "messageId", "join", "roomId", roomId, "payload", Map.of()));
        socket.nextOfType("JOINED_ROOM");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("payload");
    }

    private static final class FailingThenRecoveringPublisher extends RoomEventStreamPublisher {
        private final AtomicBoolean failing = new AtomicBoolean(true);

        private FailingThenRecoveringPublisher(
                StringRedisTemplate redisTemplate,
                ObjectMapper objectMapper,
                RoomEventStreamProperties properties,
                RoomStreamKeyFactory keyFactory,
                NodeIdentity nodeIdentity) {
            super(redisTemplate, objectMapper, properties, keyFactory, nodeIdentity);
        }

        @Override
        public Optional<PublishedRoomEvent> publishOutboxEvent(RoomEventOutboxRecord outbox) {
            if (failing.get()) {
                throw new StreamPublishException("simulated Redis publish failure", null);
            }
            return super.publishOutboxEvent(outbox);
        }

        private void recover() {
            failing.set(false);
        }
    }
}
