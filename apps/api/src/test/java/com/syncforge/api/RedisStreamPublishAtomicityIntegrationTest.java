package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Map;

import com.syncforge.api.delivery.RoomEventOutboxRepository;
import com.syncforge.api.delivery.RoomEventOutboxStatus;
import com.syncforge.api.operation.application.OperationService;
import com.syncforge.api.operation.model.OperationSubmitResult;
import com.syncforge.api.operation.model.SubmitOperationCommand;
import com.syncforge.api.stream.application.RoomEventStreamPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@TestPropertySource(properties = {
        "syncforge.redis.stream.enabled=true",
        "syncforge.rate-limit.operations-per-connection-per-second=50",
        "syncforge.rate-limit.operations-per-user-per-room-per-minute=100"
})
class RedisStreamPublishAtomicityIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    OperationService operationService;

    @MockitoBean
    RoomEventStreamPublisher streamPublisher;

    @Autowired
    RoomEventOutboxRepository outboxRepository;

    @Test
    void operationServiceDoesNotCallRedisPublisherAndCommitsOutboxIntent() {
        Fixture fixture = fixture();

        OperationSubmitResult result = operationService.submit(new SubmitOperationCommand(
                fixture.roomId(),
                fixture.editorId(),
                "atomicity-connection",
                "atomicity-session",
                "publish-delayed",
                1L,
                0L,
                "TEXT_INSERT",
                Map.of("position", 0, "text", "x")));

        assertThat(result.accepted()).isTrue();
        verifyNoInteractions(streamPublisher);
        assertThat(jdbcTemplate.queryForObject("""
                select count(*)
                from room_operations
                where room_id = ?
                """, Integer.class, fixture.roomId())).isOne();
        assertThat(getMap("/api/v1/rooms/" + fixture.roomId() + "/document-state"))
                .containsEntry("contentText", "x")
                .containsEntry("currentRoomSeq", 1)
                .containsEntry("currentRevision", 1);
        assertThat(jdbcTemplate.queryForObject("""
                select coalesce(max(current_room_seq), 0)
                from room_sequence_counters
                where room_id = ?
                """, Long.class, fixture.roomId())).isOne();
        assertThat(outboxRepository.findByRoomSeq(fixture.roomId(), 1)).isPresent();
        assertThat(outboxRepository.countByStatus(RoomEventOutboxStatus.PENDING)).isEqualTo(1);
    }
}
