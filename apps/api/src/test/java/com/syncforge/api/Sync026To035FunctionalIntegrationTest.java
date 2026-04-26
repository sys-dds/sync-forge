package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncforge.api.backpressure.application.SessionQuarantineService;
import com.syncforge.api.stream.application.RoomEventStreamConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {
        "syncforge.redis.stream.enabled=true",
        "syncforge.backpressure.warning-pending-events=1",
        "syncforge.backpressure.max-room-pending-events=2",
        "syncforge.resume.max-backfill-events=2",
        "syncforge.rate-limit.operations-per-connection-per-second=50",
        "syncforge.rate-limit.operations-per-user-per-room-per-minute=100"
})
class Sync026To035FunctionalIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    RoomEventStreamConsumer streamConsumer;

    @Autowired
    SessionQuarantineService quarantineService;

    @Test
    void endToEndFlowBackpressureStreamsQuarantineQuietRoomNodeSwitchAndReplayWork() throws Exception {
        Fixture noisy = fixture();
        Fixture quiet = fixture();
        TestSocket noisyEditor = TestSocket.connect(websocketUri(), noisy.editorId(), "noisy-editor", "noisy-editor-session", objectMapper);
        TestSocket noisyListener = TestSocket.connect(websocketUri(), noisy.ownerId(), "noisy-listener", "noisy-listener-session", objectMapper);
        TestSocket slowConsumer = TestSocket.connect(websocketUri(), noisy.viewerId(), "slow", "slow-session", objectMapper);
        TestSocket quietEditor = TestSocket.connect(websocketUri(), quiet.editorId(), "quiet-editor", "quiet-editor-session", objectMapper);
        join(noisyEditor, noisy.roomId().toString());
        join(noisyListener, noisy.roomId().toString());
        Map<String, Object> slowJoin = join(slowConsumer, noisy.roomId().toString());
        join(quietEditor, quiet.roomId().toString());
        String slowConnectionId = slowJoin.get("connectionId").toString();
        quarantineService.quarantine(noisy.roomId(), noisy.viewerId(), slowConnectionId, "slow-session", "SLOW_CONSUMER");
        noisyEditor.drain();
        noisyListener.drain();
        slowConsumer.drain();
        quietEditor.drain();

        noisyEditor.send(operationMessage("functional-noisy-1", 1, 0, Map.of("position", 0, "text", "a"), noisy.roomId().toString()));
        assertThat(payload(noisyEditor.nextOfType("OPERATION_ACK"))).containsEntry("operationId", "functional-noisy-1");
        assertThat(streamConsumer.pollRoom(noisy.roomId())).isEqualTo(1);
        assertThat(payload(noisyListener.nextOfType("OPERATION_APPLIED"))).containsEntry("operationId", "functional-noisy-1");
        assertThat(slowConsumer.hasMessageOfTypeWithin("OPERATION_APPLIED", 300)).isFalse();

        noisyEditor.send(operationMessage("functional-noisy-2", 2, 1, Map.of("position", 1, "text", "b"), noisy.roomId().toString()));
        assertThat(payload(noisyEditor.nextOfType("OPERATION_ACK"))).containsEntry("operationId", "functional-noisy-2");
        assertThat(getMap("/api/v1/rooms/" + noisy.roomId() + "/backpressure")).containsEntry("status", "REJECTING");

        noisyEditor.send(operationMessage("functional-noisy-3", 3, 2, Map.of("position", 2, "text", "c"), noisy.roomId().toString()));
        assertThat(payload(noisyEditor.nextOfType("OPERATION_NACK"))).containsEntry("code", "ROOM_BACKPRESSURE");

        quietEditor.send(operationMessage("functional-quiet-1", 1, 0, Map.of("position", 0, "text", "q"), quiet.roomId().toString()));
        assertThat(payload(quietEditor.nextOfType("OPERATION_ACK"))).containsEntry("operationId", "functional-quiet-1");
        assertThat(getMap("/api/v1/rooms/" + quiet.roomId() + "/document-state"))
                .containsEntry("contentText", "q")
                .containsEntry("currentRevision", 1);

        TestSocket resumeViewerA = TestSocket.connect(websocketUri(), noisy.viewerId(), "resume-a", "functional-resume", objectMapper);
        Map<String, Object> resumeJoin = join(resumeViewerA, noisy.roomId().toString());
        String resumeToken = payload(resumeJoin).get("resumeToken").toString();
        resumeViewerA.close();

        TestSocket resumeViewerB = TestSocket.connect(websocketUri(), noisy.viewerId(), "resume-b", "functional-resume", objectMapper);
        resumeViewerB.send(Map.of("type", "RESUME_ROOM", "messageId", "functional-node-switch", "roomId", noisy.roomId().toString(),
                "payload", Map.of("resumeToken", resumeToken, "lastSeenRoomSeq", 0)));
        resumeViewerB.nextOfType("ROOM_RESUMED");
        assertThat(events(resumeViewerB.nextOfType("ROOM_BACKFILL")))
                .extracting(event -> event.get("roomSeq"))
                .containsExactly(1, 2);

        assertThat(getMap("/api/v1/rooms/" + noisy.roomId() + "/document-state"))
                .containsEntry("contentText", "ab")
                .containsEntry("currentRevision", 2);
        assertThat(postMap("/api/v1/rooms/" + noisy.roomId() + "/document-state/rebuild", Map.of()))
                .containsEntry("currentRevision", 2)
                .containsEntry("currentRoomSeq", 2)
                .containsEntry("replayEquivalent", true);

        noisyEditor.close();
        noisyListener.close();
        slowConsumer.close();
        quietEditor.close();
        resumeViewerB.close();
    }

    private Map<String, Object> join(TestSocket socket, String roomId) throws Exception {
        socket.send(Map.of("type", "JOIN_ROOM", "messageId", "join-" + roomId, "roomId", roomId, "payload", Map.of()));
        return socket.nextOfType("JOINED_ROOM");
    }

    private Map<String, Object> operationMessage(String operationId, long clientSeq, long baseRevision, Map<String, Object> operation, String roomId) {
        return Map.of("type", "SUBMIT_OPERATION", "messageId", operationId, "roomId", roomId,
                "payload", Map.of("operationId", operationId, "clientSeq", clientSeq, "baseRevision", baseRevision,
                        "operationType", "TEXT_INSERT", "operation", operation));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("payload");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> events(Map<String, Object> envelope) {
        return (List<Map<String, Object>>) payload(envelope).get("events");
    }
}
