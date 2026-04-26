package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {
        "syncforge.rate-limit.operations-per-connection-per-second=2",
        "syncforge.rate-limit.operations-per-user-per-room-per-minute=2"
})
class OperationRateLimitIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    ObjectMapper objectMapper;

    @Test
    void perConnectionRateLimitRejectsBeforeOperationLogAndDocumentMutation() throws Exception {
        Fixture fixture = fixture();
        TestSocket editor = TestSocket.connect(websocketUri(), fixture.editorId(), "device-a", "session-a", objectMapper);
        join(editor, fixture.roomId().toString());
        editor.drain();

        editor.send(operationMessage("rate-conn-1", 1, 0, Map.of("position", 0, "text", "a"), fixture.roomId().toString()));
        assertThat(payload(editor.nextOfType("OPERATION_ACK"))).containsEntry("operationId", "rate-conn-1");
        editor.send(operationMessage("rate-conn-2", 2, 1, Map.of("position", 1, "text", "b"), fixture.roomId().toString()));
        assertThat(payload(editor.nextOfType("OPERATION_ACK"))).containsEntry("operationId", "rate-conn-2");

        editor.send(operationMessage("rate-conn-3", 3, 2, Map.of("position", 2, "text", "c"), fixture.roomId().toString()));
        Map<String, Object> limited = editor.nextOfType("RATE_LIMITED");
        assertThat(payload(limited))
                .containsEntry("code", "OPERATION_RATE_LIMITED")
                .containsEntry("limit", 2)
                .containsEntry("windowSeconds", 1);

        assertThat(getList("/api/v1/rooms/" + fixture.roomId() + "/operations"))
                .extracting(row -> row.get("operationId"))
                .containsExactly("rate-conn-1", "rate-conn-2");
        assertThat(getMap("/api/v1/rooms/" + fixture.roomId() + "/document-state"))
                .containsEntry("contentText", "ab")
                .containsEntry("currentRevision", 2);
        assertThat(getList("/api/v1/rooms/" + fixture.roomId() + "/rate-limit-events"))
                .anySatisfy(row -> assertThat(row)
                        .containsEntry("operationId", "rate-conn-3")
                        .containsEntry("decision", "REJECTED")
                        .containsEntry("limitValue", 2));
        editor.close();
    }

    @Test
    void perUserPerRoomRateLimitRejectsAcrossConnections() throws Exception {
        Fixture fixture = fixture();
        TestSocket first = TestSocket.connect(websocketUri(), fixture.editorId(), "device-a", "session-user-a", objectMapper);
        TestSocket second = TestSocket.connect(websocketUri(), fixture.editorId(), "device-b", "session-user-b", objectMapper);
        TestSocket third = TestSocket.connect(websocketUri(), fixture.editorId(), "device-c", "session-user-c", objectMapper);
        join(first, fixture.roomId().toString());
        join(second, fixture.roomId().toString());
        join(third, fixture.roomId().toString());
        first.drain();
        second.drain();
        third.drain();

        first.send(operationMessage("rate-user-1", 1, 0, Map.of("position", 0, "text", "x"), fixture.roomId().toString()));
        assertThat(payload(first.nextOfType("OPERATION_ACK"))).containsEntry("operationId", "rate-user-1");
        second.send(operationMessage("rate-user-2", 1, 1, Map.of("position", 1, "text", "y"), fixture.roomId().toString()));
        assertThat(payload(second.nextOfType("OPERATION_ACK"))).containsEntry("operationId", "rate-user-2");

        third.send(operationMessage("rate-user-3", 1, 2, Map.of("position", 2, "text", "z"), fixture.roomId().toString()));
        Map<String, Object> limited = third.nextOfType("RATE_LIMITED");
        assertThat(payload(limited))
                .containsEntry("code", "OPERATION_RATE_LIMITED")
                .containsEntry("windowSeconds", 60);

        assertThat(getList("/api/v1/rooms/" + fixture.roomId() + "/operations"))
                .extracting(row -> row.get("operationId"))
                .containsExactly("rate-user-1", "rate-user-2");
        assertThat(getMap("/api/v1/rooms/" + fixture.roomId() + "/document-state"))
                .containsEntry("contentText", "xy")
                .containsEntry("currentRevision", 2);
        assertThat(getList("/api/v1/rooms/" + fixture.roomId() + "/rate-limit-events"))
                .anySatisfy(row -> assertThat(row)
                        .containsEntry("operationId", "rate-user-3")
                        .containsEntry("decision", "REJECTED")
                        .containsEntry("windowSeconds", 60));
        first.close();
        second.close();
        third.close();
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
