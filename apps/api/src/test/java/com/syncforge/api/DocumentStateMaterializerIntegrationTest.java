package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class DocumentStateMaterializerIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    ObjectMapper objectMapper;

    @Test
    void insertDeleteReplaceNoopDuplicateRejectedAndRebuildKeepStateCorrect() throws Exception {
        Fixture fixture = fixture();
        TestSocket editor = TestSocket.connect(websocketUri(), fixture.editorId(), "editor", "state-session", objectMapper);
        join(editor, fixture.roomId().toString());

        submitAck(editor, fixture.roomId().toString(), "insert-hello", 1, 0, "TEXT_INSERT", Map.of("position", 0, "text", "hello"));
        assertThat(documentState(fixture.roomId().toString())).containsEntry("contentText", "hello").containsEntry("currentRevision", 1);

        submitAck(editor, fixture.roomId().toString(), "delete-e", 2, 1, "TEXT_DELETE", Map.of("position", 1, "length", 1));
        assertThat(documentState(fixture.roomId().toString())).containsEntry("contentText", "hllo").containsEntry("currentRevision", 2);

        submitAck(editor, fixture.roomId().toString(), "replace-ll", 3, 2, "TEXT_REPLACE", Map.of("position", 1, "length", 2, "text", "ey"));
        assertThat(documentState(fixture.roomId().toString())).containsEntry("contentText", "heyo").containsEntry("currentRevision", 3);

        submitAck(editor, fixture.roomId().toString(), "noop", 4, 3, "NOOP", Map.of());
        Map<String, Object> beforeDuplicate = documentState(fixture.roomId().toString());
        submitAck(editor, fixture.roomId().toString(), "noop", 4, 3, "NOOP", Map.of());
        assertThat(documentState(fixture.roomId().toString())).containsEntry("contentText", beforeDuplicate.get("contentText"));

        editor.send(operationMessage("bad-delete", 5, 4, "TEXT_DELETE", Map.of("position", 10, "length", 2), fixture.roomId().toString()));
        assertThat(payload(editor.nextOfType("OPERATION_NACK"))).containsEntry("code", "INVALID_OPERATION_PAYLOAD");
        assertThat(documentState(fixture.roomId().toString())).containsEntry("contentText", "heyo").containsEntry("currentRevision", 4);

        Map<String, Object> rebuilt = restTemplate.postForObject(baseUrl + "/api/v1/rooms/" + fixture.roomId() + "/document-state/rebuild",
                Map.of(), Map.class);
        assertThat(rebuilt).containsEntry("replayEquivalent", true).containsEntry("currentRevision", 4);
        Map<String, Object> state = documentState(fixture.roomId().toString());
        assertThat(state).containsEntry("contentChecksum", sha256("heyo"));
        editor.close();
    }

    private void join(TestSocket socket, String roomId) throws Exception {
        socket.send(Map.of("type", "JOIN_ROOM", "messageId", "join", "roomId", roomId, "payload", Map.of()));
        socket.nextOfType("JOINED_ROOM");
        socket.drain();
    }

    private void submitAck(TestSocket socket, String roomId, String operationId, long clientSeq, long baseRevision, String type, Map<String, Object> operation) throws Exception {
        socket.send(operationMessage(operationId, clientSeq, baseRevision, type, operation, roomId));
        socket.nextOfType("OPERATION_ACK");
    }

    private Map<String, Object> operationMessage(String operationId, long clientSeq, long baseRevision, String operationType, Map<String, Object> operation, String roomId) {
        return Map.of("type", "SUBMIT_OPERATION", "messageId", operationId, "roomId", roomId,
                "payload", Map.of("operationId", operationId, "clientSeq", clientSeq, "baseRevision", baseRevision,
                        "operationType", operationType, "operation", operation));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> documentState(String roomId) {
        return restTemplate.getForObject(baseUrl + "/api/v1/rooms/" + roomId + "/document-state", Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("payload");
    }

    private String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
