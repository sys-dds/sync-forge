package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class DuplicateCanonicalOperationIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    ObjectMapper objectMapper;

    @Test
    void semanticDuplicateWithDifferentJsonKeyOrderReturnsDuplicateAck() throws Exception {
        Fixture fixture = fixture();
        TestSocket editor = TestSocket.connect(websocketUri(), fixture.editorId(), "editor-device", "canonical-session", objectMapper);
        join(editor, fixture.roomId().toString());

        Map<String, Object> firstPayload = new LinkedHashMap<>();
        firstPayload.put("position", 0);
        firstPayload.put("text", "hello");
        editor.send(operationMessage("canonical-op", 1, 0, "TEXT_INSERT", firstPayload, fixture.roomId().toString()));
        assertThat(payload(editor.nextOfType("OPERATION_ACK"))).containsEntry("duplicate", false);

        Map<String, Object> reorderedPayload = new LinkedHashMap<>();
        reorderedPayload.put("text", "hello");
        reorderedPayload.put("position", 0);
        editor.send(operationMessage("canonical-op", 1, 0, "TEXT_INSERT", reorderedPayload, fixture.roomId().toString()));
        assertThat(payload(editor.nextOfType("OPERATION_ACK")))
                .containsEntry("duplicate", true)
                .containsEntry("roomSeq", 1)
                .containsEntry("revision", 1);

        assertThat(getList("/api/v1/rooms/" + fixture.roomId() + "/operations")).hasSize(1);
        editor.close();
    }

    @Test
    void sameOperationIdWithDifferentSemanticPayloadReturnsConflictNack() throws Exception {
        Fixture fixture = fixture();
        TestSocket editor = TestSocket.connect(websocketUri(), fixture.editorId(), "editor-device", "conflict-session", objectMapper);
        join(editor, fixture.roomId().toString());

        editor.send(operationMessage("semantic-conflict", 1, 0, "TEXT_INSERT",
                Map.of("position", 0, "text", "hello"), fixture.roomId().toString()));
        assertThat(payload(editor.nextOfType("OPERATION_ACK"))).containsEntry("duplicate", false);

        editor.send(operationMessage("semantic-conflict", 1, 0, "TEXT_INSERT",
                Map.of("position", 0, "text", "goodbye"), fixture.roomId().toString()));
        assertThat(payload(editor.nextOfType("OPERATION_NACK"))).containsEntry("code", "DUPLICATE_OPERATION_CONFLICT");
        assertThat(getMap("/api/v1/rooms/" + fixture.roomId() + "/sequence"))
                .containsEntry("currentRoomSeq", 1)
                .containsEntry("currentRevision", 1);
        editor.close();
    }

    private void join(TestSocket socket, String roomId) throws Exception {
        socket.send(Map.of("type", "JOIN_ROOM", "messageId", "join", "roomId", roomId, "payload", Map.of()));
        socket.nextOfType("JOINED_ROOM");
    }

    private Map<String, Object> operationMessage(
            String operationId,
            long clientSeq,
            long baseRevision,
            String operationType,
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
                        "operationType", operationType,
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
