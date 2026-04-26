package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import com.syncforge.api.delivery.RoomEventOutboxDispatcher;
import com.syncforge.api.delivery.RoomEventOutboxRecord;
import com.syncforge.api.delivery.RoomEventOutboxRepository;
import com.syncforge.api.delivery.RoomEventOutboxStatus;
import com.syncforge.api.node.NodeIdentity;
import com.syncforge.api.operation.application.OperationService;
import com.syncforge.api.operation.model.OperationSubmitResult;
import com.syncforge.api.operation.model.SubmitOperationCommand;
import com.syncforge.api.stream.application.RoomEventStreamProperties;
import com.syncforge.api.stream.application.RoomEventStreamPublisher;
import com.syncforge.api.stream.application.RoomStreamKeyFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {
        "syncforge.redis.stream.enabled=true",
        "syncforge.redis.stream.maxlen=10000"
})
class OutboxDispatcherIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    OperationService operationService;

    @Autowired
    RoomEventOutboxDispatcher dispatcher;

    @Autowired
    RoomEventOutboxRepository outboxRepository;

    @Autowired
    RoomEventStreamPublisher streamPublisher;

    @Autowired
    RoomEventStreamProperties streamProperties;

    @Autowired
    NodeIdentity nodeIdentity;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    RoomStreamKeyFactory keyFactory;

    @Test
    void pendingRowDispatchesToRedisAndMarksPublished() {
        Fixture fixture = fixture();
        OperationSubmitResult accepted = submit(fixture, "dispatch-success", 1, 0);

        assertThat(dispatcher.dispatchOnce(10)).isEqualTo(1);

        RoomEventOutboxRecord published = outboxRepository.findByRoomSeq(fixture.roomId(), accepted.roomSeq()).orElseThrow();
        assertThat(published.status()).isEqualTo(RoomEventOutboxStatus.PUBLISHED);
        assertThat(published.publishedStreamKey()).isEqualTo(keyFactory.roomStreamKey(fixture.roomId()));
        assertThat(published.publishedStreamId()).isNotBlank();
        assertThat(published.publishedAt()).isNotNull();
        assertThat(redisTemplate.opsForStream().range(keyFactory.roomStreamKey(fixture.roomId()), Range.unbounded()))
                .hasSize(1);
    }

    @Test
    void failedPublishRetriesThenParksAfterMaxAttempts() {
        Fixture fixture = fixture();
        RoomEventOutboxRecord invalid = outboxRepository.insertPendingOperationEvent(
                UUID.randomUUID(),
                fixture.roomId(),
                1,
                1,
                "dispatch-invalid",
                fixture.roomId() + ":1",
                Map.of("eventId", "missing-required-fields"));
        jdbcTemplate.update("update room_event_outbox set max_attempts = 2 where id = ?", invalid.id());

        assertThat(dispatcher.dispatchOnce(10)).isZero();
        RoomEventOutboxRecord retry = outboxRepository.findByRoomSeq(fixture.roomId(), 1).orElseThrow();
        assertThat(retry.status()).isEqualTo(RoomEventOutboxStatus.RETRY);
        assertThat(retry.attemptCount()).isEqualTo(1);
        assertThat(retry.nextAttemptAt()).isAfter(OffsetDateTime.now().minusSeconds(1));
        assertThat(retry.lastError()).contains("missing field");

        jdbcTemplate.update("update room_event_outbox set next_attempt_at = now() where id = ?", retry.id());
        assertThat(dispatcher.dispatchOnce(10)).isZero();
        RoomEventOutboxRecord parked = outboxRepository.findByRoomSeq(fixture.roomId(), 1).orElseThrow();
        assertThat(parked.status()).isEqualTo(RoomEventOutboxStatus.PARKED);
        assertThat(parked.parkedAt()).isNotNull();
    }

    @Test
    void expiredLockCanBeRetriedAndDispatchLimitIsDeterministic() {
        Fixture fixture = fixture();
        submit(fixture, "dispatch-limit-1", 1, 0);
        submit(fixture, "dispatch-limit-2", 2, 1);

        assertThat(outboxRepository.findDueForDispatch(1, "stale-node", java.time.Duration.ofMillis(1))).hasSize(1);
        jdbcTemplate.update("update room_event_outbox set locked_until = now() - interval '1 second' where locked_by = ?",
                "stale-node");

        assertThat(dispatcher.dispatchOnce(1)).isEqualTo(1);
        assertThat(outboxRepository.countByStatus(RoomEventOutboxStatus.PUBLISHED)).isEqualTo(1);
        assertThat(outboxRepository.countByStatus(RoomEventOutboxStatus.PENDING)).isEqualTo(1);
        assertThat(dispatcher.dispatchOnce(10)).isEqualTo(1);
        assertThat(outboxRepository.countByStatus(RoomEventOutboxStatus.PUBLISHED)).isEqualTo(2);
    }

    @Test
    void redisDisabledDispatcherDoesNotClaimRows() {
        Fixture fixture = fixture();
        submit(fixture, "dispatch-disabled", 1, 0);
        RoomEventOutboxDispatcher disabled = new RoomEventOutboxDispatcher(
                outboxRepository,
                streamPublisher,
                new RoomEventStreamProperties("syncforge:test:", 10000, 100, false),
                nodeIdentity,
                30000);

        assertThat(disabled.dispatchOnce(10)).isZero();
        assertThat(outboxRepository.countByStatus(RoomEventOutboxStatus.PENDING)).isEqualTo(1);
    }

    private OperationSubmitResult submit(Fixture fixture, String operationId, long clientSeq, long baseRevision) {
        return operationService.submit(new SubmitOperationCommand(
                fixture.roomId(),
                fixture.editorId(),
                "dispatch-connection",
                "dispatch-session",
                operationId,
                clientSeq,
                baseRevision,
                "TEXT_INSERT",
                Map.of("position", (int) baseRevision, "text", "x")));
    }
}
