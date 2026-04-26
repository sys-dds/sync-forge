package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;

import com.syncforge.api.delivery.RoomEventOutboxRepository;
import com.syncforge.api.delivery.RoomEventOutboxStatus;
import com.syncforge.api.documentstate.application.DocumentStateService;
import com.syncforge.api.documentstate.model.DocumentLiveState;
import com.syncforge.api.operation.application.OperationService;
import com.syncforge.api.operation.model.OperationRecord;
import com.syncforge.api.operation.model.OperationSubmitResult;
import com.syncforge.api.operation.model.SubmitOperationCommand;
import com.syncforge.api.operation.store.OperationRepository;
import com.syncforge.api.stream.application.RoomStreamKeyFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {
        "syncforge.redis.stream.enabled=true",
        "syncforge.redis.stream.maxlen=10000",
        "syncforge.rate-limit.operations-per-connection-per-second=100",
        "syncforge.rate-limit.operations-per-user-per-room-per-minute=200"
})
class NoLostOperationInvariantIntegrationTest extends AbstractIntegrationTest {
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

    @Autowired
    RoomEventOutboxRepository outboxRepository;

    @Test
    void ackedOperationsAreDurableReplayableAndHaveDeliveryIntent() {
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
                .isEmpty();
        assertThat(outboxRepository.countByStatus(RoomEventOutboxStatus.PENDING)).isEqualTo(4);
        assertThat(outboxRepository.findByRoomSeq(fixture.roomId(), insert.roomSeq())).isPresent();
        assertThat(outboxRepository.findByRoomSeq(fixture.roomId(), delete.roomSeq())).isPresent();
        assertThat(outboxRepository.findByRoomSeq(fixture.roomId(), noop.roomSeq())).isPresent();
        assertThat(outboxRepository.findByRoomSeq(fixture.roomId(), transformed.roomSeq())).isPresent();
    }

    @Test
    void redisDeliveryDelayDoesNotLoseAckedCanonicalOperation() {
        Fixture fixture = fixture();
        OperationSubmitResult baseline = submit(fixture, "lost-baseline", 1, 0, "TEXT_INSERT",
                Map.of("position", 0, "text", "ok"));
        OperationSubmitResult delayed = submit(fixture, "lost-publish-delayed", 2, baseline.revision(), "TEXT_INSERT",
                Map.of("position", 2, "text", "!"));

        assertThat(delayed.accepted()).isTrue();
        assertThat(operationRepository.findByRoomAndOperationId(fixture.roomId(), "lost-publish-delayed")).isPresent();
        DocumentLiveState after = documentStateService.getOrInitialize(fixture.roomId());
        assertThat(after.contentText()).isEqualTo("ok!");
        assertThat(after.currentRevision()).isEqualTo(delayed.revision());
        assertThat(after.currentRoomSeq()).isEqualTo(delayed.roomSeq());
        assertThat(jdbcTemplate.queryForObject("""
                select current_room_seq
                from room_sequence_counters
                where room_id = ?
                """, Long.class, fixture.roomId())).isEqualTo(delayed.roomSeq());
        assertThat(outboxRepository.findByRoomSeq(fixture.roomId(), delayed.roomSeq())).isPresent();
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
}
