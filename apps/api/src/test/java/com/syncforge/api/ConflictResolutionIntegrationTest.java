package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ConflictResolutionIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    ObjectMapper objectMapper;

    @Test
    void concurrentInsertInsertTransformsDeterministicallyAndPersistsTrace() throws Exception {
        Fixture fixture = fixture();
        TestSocket editor = joinedEditor(fixture, "insert-insert-session");

        submitAck(editor, fixture.roomId().toString(), "a-insert", 1, 0, "TEXT_INSERT", Map.of("position", 0, "text", "A"));
        Map<String, Object> ack = submitAck(editor, fixture.roomId().toString(), "b-insert", 2, 0, "TEXT_INSERT", Map.of("position", 0, "text", "B"));
        assertThat(payload(ack)).containsEntry("transformed", true).containsEntry("revision", 2);
        assertThat(documentState(fixture.roomId().toString())).containsEntry("contentText", "AB");
        assertThat(traceCount(fixture.roomId().toString(), "b-insert")).isEqualTo(1);
        editor.close();
    }

    @Test
    void insertDeleteAndDeleteDeleteStaleOperationsTransformOrNoopSafely() throws Exception {
        Fixture fixture = fixture();
        TestSocket editor = joinedEditor(fixture, "delete-session");

        submitAck(editor, fixture.roomId().toString(), "seed", 1, 0, "TEXT_INSERT", Map.of("position", 0, "text", "abcdef"));
        submitAck(editor, fixture.roomId().toString(), "delete-bcd", 2, 1, "TEXT_DELETE", Map.of("position", 1, "length", 3));
        Map<String, Object> insertAck = submitAck(editor, fixture.roomId().toString(), "insert-stale", 3, 1, "TEXT_INSERT", Map.of("position", 2, "text", "X"));
        assertThat(payload(insertAck)).containsEntry("transformed", true);
        assertThat(traceCount(fixture.roomId().toString(), "insert-stale")).isEqualTo(1);

        submitAck(editor, fixture.roomId().toString(), "seed-2", 4, 3, "TEXT_INSERT", Map.of("position", 0, "text", "abcdef"));
        submitAck(editor, fixture.roomId().toString(), "delete-overlap-a", 5, 4, "TEXT_DELETE", Map.of("position", 1, "length", 3));
        Map<String, Object> deleteAck = submitAck(editor, fixture.roomId().toString(), "delete-overlap-b", 6, 4, "TEXT_DELETE", Map.of("position", 2, "length", 3));
        assertThat(payload(deleteAck)).containsEntry("transformed", true);
        assertThat(traceCount(fixture.roomId().toString(), "delete-overlap-b")).isEqualTo(1);
        editor.close();
    }

    @Test
    void unsafeReplaceConflictNacksAndPersistsTrace() throws Exception {
        Fixture fixture = fixture();
        TestSocket editor = joinedEditor(fixture, "replace-session");

        submitAck(editor, fixture.roomId().toString(), "seed", 1, 0, "TEXT_INSERT", Map.of("position", 0, "text", "abc"));
        submitAck(editor, fixture.roomId().toString(), "concurrent-insert", 2, 1, "TEXT_INSERT", Map.of("position", 1, "text", "x"));
        editor.send(operationMessage("unsafe-replace", 3, 1, "TEXT_REPLACE", Map.of("position", 0, "length", 2, "text", "yy"), fixture.roomId().toString()));
        assertThat(payload(editor.nextOfType("OPERATION_NACK"))).containsEntry("code", "CONFLICT_REQUIRES_RESYNC");
        assertThat(traceCount(fixture.roomId().toString(), "unsafe-replace")).isEqualTo(1);
        editor.close();
    }

    private TestSocket joinedEditor(Fixture fixture, String session) throws Exception {
        TestSocket editor = TestSocket.connect(websocketUri(), fixture.editorId(), "editor", session, objectMapper);
        editor.send(Map.of("type", "JOIN_ROOM", "messageId", "join", "roomId", fixture.roomId().toString(), "payload", Map.of()));
        editor.nextOfType("JOINED_ROOM");
        editor.drain();
        return editor;
    }

    private Map<String, Object> submitAck(TestSocket socket, String roomId, String operationId, long clientSeq, long baseRevision, String type, Map<String, Object> operation) throws Exception {
        socket.send(operationMessage(operationId, clientSeq, baseRevision, type, operation, roomId));
        return socket.nextOfType("OPERATION_ACK");
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

    private int traceCount(String roomId, String operationId) {
        return jdbcTemplate.queryForObject("""
                select count(*) from room_conflict_resolution_traces
                where room_id = ?::uuid and operation_id = ?
                """, Integer.class, roomId, operationId);
    }
}
