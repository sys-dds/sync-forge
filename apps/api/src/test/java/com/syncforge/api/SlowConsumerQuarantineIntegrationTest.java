package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncforge.api.backpressure.application.SessionQuarantineService;
import com.syncforge.api.backpressure.application.SlowConsumerService;
import com.syncforge.api.node.NodeIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {
        "syncforge.websocket.quarantine-ttl-seconds=1",
        "syncforge.rate-limit.operations-per-connection-per-second=50",
        "syncforge.rate-limit.operations-per-user-per-room-per-minute=100",
        "syncforge.backpressure.max-room-pending-events=100",
        "syncforge.backpressure.warning-pending-events=80"
})
class SlowConsumerQuarantineIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    SlowConsumerService slowConsumerService;

    @Autowired
    SessionQuarantineService quarantineService;

    @Autowired
    NodeIdentity nodeIdentity;

    @Test
    void warningQuarantineSubmitBlockFanoutSkipAndExpiryWork() throws Exception {
        Fixture fixture = fixture();
        TestSocket editor = TestSocket.connect(websocketUri(), fixture.editorId(), "slow-device", "slow-session", objectMapper);
        TestSocket owner = TestSocket.connect(websocketUri(), fixture.ownerId(), "owner-device", "owner-session", objectMapper);
        Map<String, Object> joinedEditor = join(editor, fixture.roomId().toString());
        join(owner, fixture.roomId().toString());
        String editorConnectionId = joinedEditor.get("connectionId").toString();
        editor.drain();
        owner.drain();

        slowConsumerService.warn(fixture.roomId(), fixture.editorId(), editorConnectionId, nodeIdentity.nodeId(), 80, 80);
        assertThat(getList("/api/v1/rooms/" + fixture.roomId() + "/slow-consumers"))
                .anySatisfy(row -> assertThat(row)
                        .containsEntry("connectionId", editorConnectionId)
                        .containsEntry("decision", "WARNED"));

        slowConsumerService.quarantined(fixture.roomId(), fixture.editorId(), editorConnectionId, nodeIdentity.nodeId(), 100, 100);
        quarantineService.quarantine(fixture.roomId(), fixture.editorId(), editorConnectionId, "slow-session", "SLOW_CONSUMER");
        assertThat(getList("/api/v1/rooms/" + fixture.roomId() + "/quarantines"))
                .anySatisfy(row -> assertThat(row)
                        .containsEntry("connectionId", editorConnectionId)
                        .containsEntry("reason", "SLOW_CONSUMER"));

        editor.send(operationMessage("quarantined-submit", 1, 0, Map.of("position", 0, "text", "blocked"), fixture.roomId().toString()));
        assertThat(payload(editor.nextOfType("SESSION_QUARANTINED")))
                .containsEntry("connectionId", editorConnectionId)
                .containsEntry("reason", "SLOW_CONSUMER");
        assertThat(getList("/api/v1/rooms/" + fixture.roomId() + "/operations")).isEmpty();
        assertThat(getMap("/api/v1/rooms/" + fixture.roomId() + "/document-state"))
                .containsEntry("contentText", "")
                .containsEntry("currentRevision", 0);

        owner.send(operationMessage("owner-visible", 1, 0, Map.of("position", 0, "text", "a"), fixture.roomId().toString()));
        assertThat(payload(owner.nextOfType("OPERATION_ACK"))).containsEntry("operationId", "owner-visible");
        assertThat(editor.hasMessageOfTypeWithin("OPERATION_APPLIED", 300)).isFalse();

        Thread.sleep(1100);
        assertThat(quarantineService.releaseExpired()).isGreaterThanOrEqualTo(1);
        editor.send(operationMessage("released-submit", 2, 1, Map.of("position", 1, "text", "b"), fixture.roomId().toString()));
        assertThat(payload(editor.nextOfType("OPERATION_ACK"))).containsEntry("operationId", "released-submit");
        assertThat(getMap("/api/v1/rooms/" + fixture.roomId() + "/document-state"))
                .containsEntry("contentText", "ab")
                .containsEntry("currentRevision", 2);

        editor.close();
        owner.close();
    }

    private Map<String, Object> join(TestSocket socket, String roomId) throws Exception {
        socket.send(Map.of("type", "JOIN_ROOM", "messageId", "join-" + roomId, "roomId", roomId, "payload", Map.of()));
        return socket.nextOfType("JOINED_ROOM");
    }

    private Map<String, Object> operationMessage(
            String operationId,
            long clientSeq,
            long baseRevision,
            Map<String, Object> operation,
            String roomId) {
        return Map.of(
                "type", "SUBMIT_OPERATION",
                "messageId", operationId + "-message",
                "roomId", roomId,
                "payload", Map.of(
                        "operationId", operationId,
                        "clientSeq", clientSeq,
                        "baseRevision", baseRevision,
                        "operationType", "TEXT_INSERT",
                        "operation", operation));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("payload");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getList(String path) {
        return restTemplate.getForObject(baseUrl + path, List.class);
    }
}
