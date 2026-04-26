package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {
        "syncforge.backpressure.warning-pending-events=1",
        "syncforge.backpressure.max-room-pending-events=2",
        "syncforge.rate-limit.operations-per-connection-per-second=50",
        "syncforge.rate-limit.operations-per-user-per-room-per-minute=100"
})
class RoomBackpressureIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    ObjectMapper objectMapper;

    @Test
    void warningRejectingEssentialBypassAndQuietRoomIsolationWork() throws Exception {
        Fixture noisy = fixture();
        Fixture quiet = fixture();
        TestSocket editor = TestSocket.connect(websocketUri(), noisy.editorId(), "noisy-device", "noisy-session", objectMapper);
        TestSocket quietEditor = TestSocket.connect(websocketUri(), quiet.editorId(), "quiet-device", "quiet-session", objectMapper);
        join(editor, noisy.roomId().toString());
        join(quietEditor, quiet.roomId().toString());
        editor.drain();
        quietEditor.drain();

        editor.send(operationMessage("backpressure-1", 1, 0, Map.of("position", 0, "text", "a"), noisy.roomId().toString()));
        assertThat(payload(editor.nextOfType("OPERATION_ACK"))).containsEntry("operationId", "backpressure-1");
        assertThat(payload(editor.nextOfType("BACKPRESSURE_WARNING")))
                .containsEntry("status", "WARNING")
                .containsEntry("pendingEvents", 1)
                .containsEntry("maxPendingEvents", 2);

        editor.send(operationMessage("backpressure-2", 2, 1, Map.of("position", 1, "text", "b"), noisy.roomId().toString()));
        assertThat(payload(editor.nextOfType("OPERATION_ACK"))).containsEntry("operationId", "backpressure-2");
        assertThat(payload(editor.nextOfType("BACKPRESSURE_WARNING")))
                .containsEntry("status", "REJECTING")
                .containsEntry("pendingEvents", 2);

        editor.send(operationMessage("backpressure-3", 3, 2, Map.of("position", 2, "text", "c"), noisy.roomId().toString()));
        assertThat(payload(editor.nextOfType("OPERATION_NACK"))).containsEntry("code", "ROOM_BACKPRESSURE");
        assertThat(getList("/api/v1/rooms/" + noisy.roomId() + "/operations"))
                .extracting(row -> row.get("operationId"))
                .containsExactly("backpressure-1", "backpressure-2");
        assertThat(getMap("/api/v1/rooms/" + noisy.roomId() + "/document-state"))
                .containsEntry("contentText", "ab")
                .containsEntry("currentRevision", 2);
        assertThat(getMap("/api/v1/rooms/" + noisy.roomId() + "/backpressure"))
                .containsEntry("status", "REJECTING")
                .containsEntry("pendingEvents", 2);

        editor.send(Map.of("type", "PING", "messageId", "ping-while-rejecting", "roomId", noisy.roomId().toString(), "payload", Map.of()));
        assertThat(editor.nextOfType("PONG")).containsEntry("messageId", "ping-while-rejecting");
        editor.send(Map.of("type", "GET_DOCUMENT_STATE", "messageId", "state-while-rejecting", "roomId", noisy.roomId().toString(), "payload", Map.of()));
        assertThat(editor.nextOfType("DOCUMENT_STATE")).containsEntry("messageId", "state-while-rejecting");

        quietEditor.send(operationMessage("quiet-1", 1, 0, Map.of("position", 0, "text", "q"), quiet.roomId().toString()));
        assertThat(payload(quietEditor.nextOfType("OPERATION_ACK"))).containsEntry("operationId", "quiet-1");
        assertThat(getMap("/api/v1/rooms/" + quiet.roomId() + "/document-state"))
                .containsEntry("contentText", "q")
                .containsEntry("currentRevision", 1);

        editor.close();
        quietEditor.close();
    }

    private void join(TestSocket socket, String roomId) throws Exception {
        socket.send(Map.of("type", "JOIN_ROOM", "messageId", "join-" + roomId, "roomId", roomId, "payload", Map.of()));
        socket.nextOfType("JOINED_ROOM");
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
