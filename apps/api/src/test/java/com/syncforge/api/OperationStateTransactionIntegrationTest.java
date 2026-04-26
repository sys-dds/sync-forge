package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class OperationStateTransactionIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    ObjectMapper objectMapper;

    @Test
    void invalidStateApplyDoesNotPersistAcceptedOperationOrAdvanceState() throws Exception {
        Fixture fixture = fixture();
        TestSocket editor = joinedEditor(fixture);

        editor.send(operationMessage("invalid-insert", 1, 0, "TEXT_INSERT", Map.of("position", 99, "text", "bad"), fixture.roomId().toString()));
        assertThat(payload(editor.nextOfType("OPERATION_NACK"))).containsEntry("code", "INVALID_OPERATION_PAYLOAD");
        assertThat(jdbcTemplate.queryForObject("select count(*) from room_operations where room_id = ?", Integer.class, fixture.roomId())).isZero();
        assertThat(documentState(fixture.roomId().toString())).containsEntry("contentText", "").containsEntry("currentRevision", 0);
        editor.close();
    }

    @Test
    void operationLogAndStateDoNotDriftAcrossAcceptedOperations() throws Exception {
        Fixture fixture = fixture();
        TestSocket editor = joinedEditor(fixture);

        submitAck(editor, fixture.roomId().toString(), "one", 1, 0, "TEXT_INSERT", Map.of("position", 0, "text", "a"));
        submitAck(editor, fixture.roomId().toString(), "two", 2, 1, "TEXT_INSERT", Map.of("position", 1, "text", "b"));
        submitAck(editor, fixture.roomId().toString(), "three", 3, 2, "TEXT_DELETE", Map.of("position", 0, "length", 1));

        Map<String, Object> state = documentState(fixture.roomId().toString());
        assertThat(state).containsEntry("contentText", "b").containsEntry("currentRoomSeq", 3).containsEntry("currentRevision", 3);
        assertThat(jdbcTemplate.queryForObject("select max(room_seq) from room_operations where room_id = ?", Long.class, fixture.roomId())).isEqualTo(3);
        editor.close();
    }

    private TestSocket joinedEditor(Fixture fixture) throws Exception {
        TestSocket editor = TestSocket.connect(websocketUri(), fixture.editorId(), "editor", "tx-session", objectMapper);
        editor.send(Map.of("type", "JOIN_ROOM", "messageId", "join", "roomId", fixture.roomId().toString(), "payload", Map.of()));
        editor.nextOfType("JOINED_ROOM");
        editor.drain();
        return editor;
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
}
