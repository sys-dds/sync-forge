package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import com.syncforge.api.delivery.RoomEventOutboxDispatcher;
import com.syncforge.api.documentstate.application.DocumentStateService;
import com.syncforge.api.documentstate.model.DocumentLiveState;
import com.syncforge.api.operation.application.OperationService;
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
class NoDuplicateOperationInvariantIntegrationTest extends AbstractIntegrationTest {
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
    RoomEventOutboxDispatcher outboxDispatcher;

    @Test
    void duplicateOperationIdReusesOriginalAckWithoutSecondRowStreamOrMutation() {
        Fixture fixture = fixture();
        redisTemplate.delete(keyFactory.roomStreamKey(fixture.roomId()));

        OperationSubmitResult first = submit(fixture, "duplicate-safe-1", "session-a", 1, 0, "TEXT_INSERT",
                Map.of("position", 0, "text", "x"));
        DocumentLiveState afterFirst = documentStateService.getOrInitialize(fixture.roomId());
        OperationSubmitResult duplicate = submit(fixture, "duplicate-safe-1", "session-a", 2, 0, "TEXT_INSERT",
                Map.of("position", 0, "text", "x"));
        OperationSubmitResult reconnectDuplicate = submit(fixture, "duplicate-safe-1", "session-after-reconnect", 1, 0,
                "TEXT_INSERT", Map.of("position", 0, "text", "x"));

        assertThat(first.accepted()).isTrue();
        assertThat(duplicate.accepted()).isTrue();
        assertThat(duplicate.duplicate()).isTrue();
        assertThat(duplicate.roomSeq()).isEqualTo(first.roomSeq());
        assertThat(duplicate.revision()).isEqualTo(first.revision());
        assertThat(reconnectDuplicate.accepted()).isTrue();
        assertThat(reconnectDuplicate.duplicate()).isTrue();
        assertThat(reconnectDuplicate.roomSeq()).isEqualTo(first.roomSeq());
        assertThat(reconnectDuplicate.revision()).isEqualTo(first.revision());

        assertThat(operationRepository.findByRoomAndOperationId(fixture.roomId(), "duplicate-safe-1")).isPresent();
        assertThat(jdbcTemplate.queryForObject("""
                select count(*)
                from room_operations
                where room_id = ? and operation_id = ?
                """, Integer.class, fixture.roomId(), "duplicate-safe-1")).isOne();
        assertThat(outboxDispatcher.dispatchOnce(10)).isEqualTo(1);
        assertThat(redisTemplate.opsForStream().range(keyFactory.roomStreamKey(fixture.roomId()), Range.unbounded()))
                .hasSize(1);
        assertThat(documentStateService.getOrInitialize(fixture.roomId()).contentText())
                .isEqualTo(afterFirst.contentText())
                .isEqualTo("x");
    }

    @Test
    void sameOperationIdWithDifferentPayloadNacksButDifferentRoomIsAllowed() {
        Fixture fixture = fixture();
        Fixture otherFixture = fixture();

        OperationSubmitResult first = submit(fixture, "duplicate-conflict-1", "session-a", 1, 0, "TEXT_INSERT",
                Map.of("position", 0, "text", "x"));
        OperationSubmitResult conflict = submit(fixture, "duplicate-conflict-1", "session-a", 2, 0, "TEXT_INSERT",
                Map.of("position", 0, "text", "y"));
        OperationSubmitResult otherRoom = submit(otherFixture, "duplicate-conflict-1", "session-b", 1, 0, "TEXT_INSERT",
                Map.of("position", 0, "text", "y"));

        assertThat(first.accepted()).isTrue();
        assertThat(conflict.accepted()).isFalse();
        assertThat(conflict.code()).isEqualTo("DUPLICATE_OPERATION_CONFLICT");
        assertThat(otherRoom.accepted()).isTrue();
        assertThat(otherRoom.roomSeq()).isEqualTo(1);
        assertThat(documentStateService.getOrInitialize(fixture.roomId()).contentText()).isEqualTo("x");
        assertThat(documentStateService.getOrInitialize(otherFixture.roomId()).contentText()).isEqualTo("y");
    }

    private OperationSubmitResult submit(
            Fixture fixture,
            String operationId,
            String clientSessionId,
            long clientSeq,
            long baseRevision,
            String operationType,
            Map<String, Object> operation) {
        return operationService.submit(new SubmitOperationCommand(
                fixture.roomId(),
                fixture.editorId(),
                "no-duplicate-connection",
                clientSessionId,
                operationId,
                clientSeq,
                baseRevision,
                operationType,
                operation));
    }
}
