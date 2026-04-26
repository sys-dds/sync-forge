package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncforge.api.delivery.RoomEventOutboxDispatcher;
import com.syncforge.api.delivery.RoomEventOutboxRepository;
import com.syncforge.api.delivery.RoomEventOutboxStatus;
import com.syncforge.api.documentstate.application.DocumentStateService;
import com.syncforge.api.documentstate.model.DocumentLiveState;
import com.syncforge.api.operation.application.OperationService;
import com.syncforge.api.operation.model.OperationSubmitResult;
import com.syncforge.api.operation.model.SubmitOperationCommand;
import com.syncforge.api.resume.application.RoomBackfillService;
import com.syncforge.api.resume.model.BackfillResult;
import com.syncforge.api.stream.application.RoomEventStreamConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "syncforge.redis.stream.enabled=true")
class DeliveryTruthRecoveryIntegrationTest extends AbstractIntegrationTest {
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

    @Test
    void delayedOutboxPublishRecoversAndDeliversOnceWhileBackfillUsesDbTruth() throws Exception {
        Fixture fixture = fixture();
        TestSocket listener = TestSocket.connect(websocketUri(), fixture.viewerId(), "recovery-device",
                "recovery-session", objectMapper);
        join(listener, fixture.roomId().toString());
        listener.drain();

        OperationSubmitResult accepted = submit(fixture, "recovery-op", 1, 0, "r");
        assertThat(accepted.accepted()).isTrue();
        assertThat(outboxRepository.findByRoomSeq(fixture.roomId(), accepted.roomSeq()).orElseThrow().status())
                .isEqualTo(RoomEventOutboxStatus.PENDING);
        assertThat(documentStateService.getOrInitialize(fixture.roomId()).contentText()).isEqualTo("r");

        BackfillResult beforePublish = roomBackfillService.backfill(fixture.roomId(), fixture.viewerId(), "recovery-missed", 0);
        assertThat(beforePublish.events()).extracting(event -> event.get("operationId")).containsExactly("recovery-op");

        assertThat(dispatcher.dispatchOnce(10)).isEqualTo(1);
        assertThat(outboxRepository.findByRoomSeq(fixture.roomId(), accepted.roomSeq()).orElseThrow().status())
                .isEqualTo(RoomEventOutboxStatus.PUBLISHED);
        assertThat(streamConsumer.pollRoom(fixture.roomId())).isEqualTo(1);
        assertThat(payload(listener.nextOfType("OPERATION_APPLIED"))).containsEntry("operationId", "recovery-op");
        assertThat(streamConsumer.pollRoom(fixture.roomId())).isZero();
        assertThat(listener.hasMessageOfTypeWithin("OPERATION_APPLIED", 250)).isFalse();

        DocumentLiveState live = documentStateService.getOrInitialize(fixture.roomId());
        DocumentLiveState replay = documentStateService.rebuildFromOperationLog(fixture.roomId()).state();
        assertThat(replay.contentText()).isEqualTo(live.contentText());
        assertThat(replay.currentRoomSeq()).isEqualTo(live.currentRoomSeq());
        assertThat(replay.currentRevision()).isEqualTo(live.currentRevision());

        listener.close();
    }

    private OperationSubmitResult submit(Fixture fixture, String operationId, long clientSeq, long baseRevision, String text) {
        return operationService.submit(new SubmitOperationCommand(
                fixture.roomId(),
                fixture.editorId(),
                "recovery-connection",
                "recovery-session",
                operationId,
                clientSeq,
                baseRevision,
                "TEXT_INSERT",
                Map.of("position", (int) baseRevision, "text", text)));
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
