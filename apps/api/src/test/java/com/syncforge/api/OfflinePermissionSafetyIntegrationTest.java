package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.syncforge.api.delivery.RoomEventOutboxRepository;
import com.syncforge.api.operation.application.CanonicalOperationPayloadHasher;
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

@TestPropertySource(properties = "syncforge.redis.stream.enabled=true")
class OfflinePermissionSafetyIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    OperationService operationService;

    @Autowired
    CanonicalOperationPayloadHasher payloadHasher;

    @Autowired
    OperationRepository operationRepository;

    @Autowired
    RoomEventOutboxRepository outboxRepository;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    RoomStreamKeyFactory keyFactory;

    @Test
    void removedMemberCannotSubmitOfflineOperationAndErrorDoesNotLeakPayload() {
        Fixture fixture = fixture();
        redisTemplate.delete(keyFactory.roomStreamKey(fixture.roomId()));
        removeMember(fixture.roomId(), fixture.editorId());

        OperationSubmitResult rejected = submitOffline(fixture, "offline-denied", "client-denied", 1,
                Map.of("position", 0, "text", "secret-denied"));

        assertThat(rejected.accepted()).isFalse();
        assertThat(rejected.code()).isEqualTo("EDIT_PERMISSION_REQUIRED");
        assertThat(rejected.message()).doesNotContain("secret-denied");
        assertThat(operationRepository.findByRoom(fixture.roomId())).isEmpty();
        assertThat(outboxRepository.findByRoomSeq(fixture.roomId(), 1)).isEmpty();
        assertThat(getMap("/api/v1/rooms/" + fixture.roomId() + "/sequence"))
                .containsEntry("currentRoomSeq", 0)
                .containsEntry("currentRevision", 0);
        assertThat(redisTemplate.opsForStream().range(keyFactory.roomStreamKey(fixture.roomId()), Range.unbounded()))
                .isNullOrEmpty();
    }

    @Test
    void staleAcceptedOfflineRetryCannotBypassPermissionRemoval() {
        Fixture fixture = fixture();
        OperationSubmitResult accepted = submitOffline(fixture, "offline-before-removal", "client-before-removal", 1,
                Map.of("position", 0, "text", "a"));
        removeMember(fixture.roomId(), fixture.editorId());

        OperationSubmitResult rejectedRetry = submitOffline(fixture, "offline-before-removal-retry", "client-before-removal", 99,
                Map.of("position", 0, "text", "a"));

        assertThat(accepted.accepted()).isTrue();
        assertThat(rejectedRetry.accepted()).isFalse();
        assertThat(rejectedRetry.code()).isEqualTo("EDIT_PERMISSION_REQUIRED");
        assertThat(operationRepository.findByRoom(fixture.roomId())).hasSize(1);
        assertThat(outboxRepository.findByRoomSeq(fixture.roomId(), accepted.roomSeq())).isPresent();
        assertThat(outboxRepository.findByRoomSeq(fixture.roomId(), accepted.roomSeq() + 1)).isEmpty();
        assertThat(getMap("/api/v1/rooms/" + fixture.roomId() + "/sequence"))
                .containsEntry("currentRoomSeq", 1)
                .containsEntry("currentRevision", 1);
    }

    private OperationSubmitResult submitOffline(
            Fixture fixture,
            String operationId,
            String clientOperationId,
            long clientSeq,
            Map<String, Object> operation) {
        return operationService.submit(new SubmitOperationCommand(
                fixture.roomId(),
                fixture.editorId(),
                "offline-permission-connection",
                "offline-permission-session",
                operationId,
                clientSeq,
                0L,
                "TEXT_INSERT",
                operation,
                true,
                clientOperationId,
                0L,
                null,
                List.of(),
                payloadHasher.hash("TEXT_INSERT", operation)));
    }

    private void removeMember(java.util.UUID roomId, java.util.UUID userId) {
        jdbcTemplate.update("""
                update room_memberships
                set status = 'REMOVED'
                where room_id = ? and user_id = ?
                """, roomId, userId);
    }
}
