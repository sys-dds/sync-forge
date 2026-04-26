package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncforge.api.delivery.RoomEventOutboxDispatcher;
import com.syncforge.api.delivery.RoomEventOutboxRepository;
import com.syncforge.api.delivery.RoomEventOutboxStatus;
import com.syncforge.api.documentstate.application.DocumentStateService;
import com.syncforge.api.operation.application.OperationService;
import com.syncforge.api.operation.model.OperationSubmitResult;
import com.syncforge.api.operation.model.SubmitOperationCommand;
import com.syncforge.api.resume.application.RoomBackfillService;
import com.syncforge.api.resume.model.BackfillResult;
import com.syncforge.api.shared.ForbiddenException;
import com.syncforge.api.stream.application.RoomEventStreamConsumer;
import com.syncforge.api.stream.application.RoomStreamKeyFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "syncforge.redis.stream.enabled=true")
class Sync056To065FunctionalIntegrationTest extends AbstractIntegrationTest {
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
    RoomBackfillService roomBackfillService;

    @Autowired
    DocumentStateService documentStateService;

    @Autowired
    RoomStreamKeyFactory keyFactory;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Test
    void deliveryTruthEndToEndWithAckDuplicatePublishBackfillAndPermissionSafety() throws Exception {
        Fixture fixture = fixture();
        TestSocket activeClient = TestSocket.connect(websocketUri(), fixture.ownerId(), "sync056-active-device",
                "sync056-active-session", objectMapper);
        TestSocket missedClient = TestSocket.connect(websocketUri(), fixture.viewerId(), "sync056-missed-device",
                "sync056-missed-session", objectMapper);
        join(activeClient, fixture.roomId().toString());
        join(missedClient, fixture.roomId().toString());
        activeClient.drain();
        missedClient.drain();

        OperationSubmitResult accepted = submit(fixture, "sync056-op", 1, 0);
        assertThat(outboxRepository.findByRoomSeq(fixture.roomId(), accepted.roomSeq()).orElseThrow().status())
                .isEqualTo(RoomEventOutboxStatus.PENDING);

        assertThat(dispatcher.dispatchOnce(10)).isEqualTo(1);
        assertThat(outboxRepository.findByRoomSeq(fixture.roomId(), accepted.roomSeq()).orElseThrow().status())
                .isEqualTo(RoomEventOutboxStatus.PUBLISHED);
        duplicateFirstStreamRecord(fixture);

        assertThat(streamConsumer.pollRoom(fixture.roomId())).isEqualTo(1);
        assertThat(payload(activeClient.nextOfType("OPERATION_APPLIED"))).containsEntry("operationId", "sync056-op");
        assertThat(payload(missedClient.nextOfType("OPERATION_APPLIED"))).containsEntry("operationId", "sync056-op");
        assertThat(activeClient.hasMessageOfTypeWithin("OPERATION_APPLIED", 250)).isFalse();

        activeClient.send(Map.of("type", "ACK_ROOM_EVENT", "messageId", "ack", "roomId", fixture.roomId().toString(),
                "payload", Map.of("roomSeq", accepted.roomSeq())));
        assertThat(((Number) payload(activeClient.nextOfType("ROOM_EVENT_ACKED")).get("roomSeq")).longValue())
                .isEqualTo(accepted.roomSeq());

        BackfillResult backfill = roomBackfillService.backfill(fixture.roomId(), fixture.viewerId(), "sync056-late", 0);
        assertThat(backfill.events()).extracting(event -> event.get("operationId")).containsExactly("sync056-op");

        jdbcTemplate.update("""
                update room_memberships
                set status = 'REMOVED'
                where room_id = ? and user_id = ?
                """, fixture.roomId(), fixture.viewerId());
        assertThatThrownBy(() -> roomBackfillService.backfill(fixture.roomId(), fixture.viewerId(), "sync056-removed", 0))
                .isInstanceOf(ForbiddenException.class);

        assertThat(documentStateService.getOrInitialize(fixture.roomId()).contentText()).isEqualTo("z");
        activeClient.close();
        missedClient.close();
    }

    private OperationSubmitResult submit(Fixture fixture, String operationId, long clientSeq, long baseRevision) {
        return operationService.submit(new SubmitOperationCommand(
                fixture.roomId(),
                fixture.editorId(),
                "sync056-connection",
                "sync056-session",
                operationId,
                clientSeq,
                baseRevision,
                "TEXT_INSERT",
                Map.of("position", (int) baseRevision, "text", "z")));
    }

    private void duplicateFirstStreamRecord(Fixture fixture) {
        String streamKey = keyFactory.roomStreamKey(fixture.roomId());
        MapRecord<String, Object, Object> original = redisTemplate.opsForStream()
                .range(streamKey, Range.unbounded())
                .getFirst();
        redisTemplate.opsForStream().add(streamKey, original.getValue());
    }

    private void join(TestSocket socket, String roomId) throws Exception {
        socket.send(Map.of("type", "JOIN_ROOM", "messageId", "join", "roomId", roomId, "payload", Map.of()));
        socket.nextOfType("JOINED_ROOM");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("payload");
    }
}
