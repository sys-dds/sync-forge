package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CapabilityGateIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    ObjectMapper objectMapper;

    @Test
    void missingOperationsRejectsSubmitWithoutOperationLogOrDocumentMutation() throws Exception {
        Fixture fixture = fixture();
        TestSocket socket = joinV2(fixture.editorId(), fixture.roomId().toString(), "no-ops", List.of("SNAPSHOT"));

        socket.send(operation(fixture.roomId().toString(), "blocked-op", 1, 0, "hello"));

        Map<String, Object> nack = payload(socket.nextOfType("OPERATION_NACK"));
        assertThat(nack).containsEntry("code", "OPERATIONS_NOT_NEGOTIATED");
        assertThat(countRows("room_operations", fixture.roomId())).isZero();

        socket.send(Map.of("type", "GET_DOCUMENT_STATE", "messageId", "state", "roomId", fixture.roomId().toString(), "payload", Map.of()));
        assertThat(payload(socket.nextOfType("DOCUMENT_STATE"))).containsEntry("contentText", "");

        socket.close();
    }

    @Test
    void missingAwarenessRejectsCursorAndSelectionWithoutAwarenessState() throws Exception {
        Fixture fixture = fixture();
        TestSocket socket = joinV2(fixture.viewerId(), fixture.roomId().toString(), "no-awareness", List.of("SNAPSHOT"));

        socket.send(Map.of(
                "type", "CURSOR_UPDATE",
                "messageId", "cursor",
                "roomId", fixture.roomId().toString(),
                "payload", Map.of("cursorPosition", 1)));
        assertThat(payload(socket.nextOfType("ERROR"))).containsEntry("code", "AWARENESS_NOT_NEGOTIATED");

        socket.send(Map.of(
                "type", "SELECTION_UPDATE",
                "messageId", "selection",
                "roomId", fixture.roomId().toString(),
                "payload", Map.of("anchorPosition", 0, "focusPosition", 1)));
        assertThat(payload(socket.nextOfType("ERROR"))).containsEntry("code", "AWARENESS_NOT_NEGOTIATED");
        assertThat(countRows("room_awareness_states", fixture.roomId())).isZero();

        socket.close();
    }

    @Test
    void missingSnapshotRejectsDocumentStateWithoutLeakingContent() throws Exception {
        Fixture fixture = fixture();
        TestSocket editor = joinV2(fixture.editorId(), fixture.roomId().toString(), "editor", List.of("OPERATIONS"));
        editor.send(operation(fixture.roomId().toString(), "content-seed", 1, 0, "secret"));
        editor.nextOfType("OPERATION_ACK");

        editor.send(Map.of("type", "GET_DOCUMENT_STATE", "messageId", "blocked-state", "roomId", fixture.roomId().toString(), "payload", Map.of()));
        Map<String, Object> error = payload(editor.nextOfType("ERROR"));

        assertThat(error).containsEntry("code", "SNAPSHOT_NOT_NEGOTIATED");
        assertThat(error).doesNotContainKey("contentText");
        assertThat(error).doesNotContainEntry("message", "secret");

        editor.close();
    }

    @Test
    void missingResumeOrBackfillRejectsResumeWithoutOffsetMutation() throws Exception {
        Fixture fixture = fixture();
        TestSocket legacy = TestSocket.connect(websocketUri(), fixture.viewerId(), "legacy-device", "resume-session", objectMapper);
        legacy.send(Map.of("type", "JOIN_ROOM", "messageId", "join", "roomId", fixture.roomId().toString(), "payload", Map.of()));
        String token = payload(legacy.nextOfType("JOINED_ROOM")).get("resumeToken").toString();
        legacy.close();

        TestSocket missingResume = TestSocket.connect(websocketUri(), fixture.viewerId(), "resume-device", "resume-session", objectMapper);
        missingResume.send(resume(fixture.roomId().toString(), token, List.of("BACKFILL")));
        assertThat(payload(missingResume.nextOfType("ERROR"))).containsEntry("code", "RESUME_NOT_NEGOTIATED");
        missingResume.close();

        TestSocket missingBackfill = TestSocket.connect(websocketUri(), fixture.viewerId(), "resume-device-2", "resume-session", objectMapper);
        missingBackfill.send(resume(fixture.roomId().toString(), token, List.of("RESUME")));
        assertThat(payload(missingBackfill.nextOfType("ERROR"))).containsEntry("code", "BACKFILL_NOT_NEGOTIATED");
        missingBackfill.close();

        assertThat(countRows("room_client_offsets", fixture.roomId())).isZero();
    }

    @Test
    void legacyV1DefaultsPreserveExistingSubmitBehavior() throws Exception {
        Fixture fixture = fixture();
        TestSocket editor = TestSocket.connect(websocketUri(), fixture.editorId(), "legacy-device", "legacy-submit", objectMapper);
        editor.send(Map.of("type", "JOIN_ROOM", "messageId", "join", "roomId", fixture.roomId().toString(), "payload", Map.of()));
        editor.nextOfType("JOINED_ROOM");

        editor.send(operation(fixture.roomId().toString(), "legacy-op", 1, 0, "ok"));

        assertThat(payload(editor.nextOfType("OPERATION_ACK"))).containsEntry("operationId", "legacy-op");
        assertThat(countRows("room_operations", fixture.roomId())).isEqualTo(1);

        editor.close();
    }

    private TestSocket joinV2(java.util.UUID userId, String roomId, String clientId, List<String> capabilities) throws Exception {
        TestSocket socket = TestSocket.connect(websocketUri(), userId, clientId + "-device", clientId + "-session", objectMapper);
        socket.send(Map.of(
                "type", "JOIN_ROOM",
                "messageId", "join-" + clientId,
                "roomId", roomId,
                "payload", Map.of(
                        "protocolVersion", 2,
                        "clientId", clientId,
                        "capabilities", capabilities)));
        socket.nextOfType("JOINED_ROOM");
        return socket;
    }

    private Map<String, Object> operation(String roomId, String operationId, long clientSeq, long baseRevision, String text) {
        return Map.of(
                "type", "SUBMIT_OPERATION",
                "messageId", operationId,
                "roomId", roomId,
                "payload", Map.of(
                        "operationId", operationId,
                        "clientSeq", clientSeq,
                        "baseRevision", baseRevision,
                        "operationType", "TEXT_INSERT",
                        "operation", Map.of("position", 0, "text", text)));
    }

    private Map<String, Object> resume(String roomId, String token, List<String> capabilities) {
        return Map.of(
                "type", "RESUME_ROOM",
                "messageId", "resume-" + capabilities,
                "roomId", roomId,
                "payload", Map.of(
                        "protocolVersion", 2,
                        "capabilities", capabilities,
                        "resumeToken", token,
                        "lastSeenRoomSeq", 0));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("payload");
    }

    private int countRows(String table, java.util.UUID roomId) {
        return jdbcTemplate.queryForObject("select count(*) from " + table + " where room_id = ?", Integer.class, roomId);
    }
}
