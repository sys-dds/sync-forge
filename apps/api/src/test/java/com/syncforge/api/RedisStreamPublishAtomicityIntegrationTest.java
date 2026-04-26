package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

import java.util.Map;

import com.syncforge.api.operation.application.OperationService;
import com.syncforge.api.operation.model.SubmitOperationCommand;
import com.syncforge.api.stream.application.RoomEventStreamPublisher;
import com.syncforge.api.stream.application.StreamPublishException;
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

    @Test
    void requiredPublishFailureRollsBackAcceptedOperationStateAndSequence() {
        Fixture fixture = fixture();
        doThrow(new StreamPublishException("forced publish failure", null))
                .when(streamPublisher).publishAcceptedOperation(any(), eq(false));

        assertThatThrownBy(() -> operationService.submit(new SubmitOperationCommand(
                fixture.roomId(),
                fixture.editorId(),
                "atomicity-connection",
                "atomicity-session",
                "publish-fails",
                1L,
                0L,
                "TEXT_INSERT",
                Map.of("position", 0, "text", "x"))))
                .isInstanceOf(StreamPublishException.class)
                .hasMessageContaining("forced publish failure");

        assertThat(jdbcTemplate.queryForObject("""
                select count(*)
                from room_operations
                where room_id = ?
                """, Integer.class, fixture.roomId())).isZero();
        assertThat(getMap("/api/v1/rooms/" + fixture.roomId() + "/document-state"))
                .containsEntry("contentText", "")
                .containsEntry("currentRoomSeq", 0)
                .containsEntry("currentRevision", 0);
        assertThat(jdbcTemplate.queryForObject("""
                select coalesce(max(current_room_seq), 0)
                from room_sequence_counters
                where room_id = ?
                """, Long.class, fixture.roomId())).isZero();
    }
}
