package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class SnapshotReplayIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    ObjectMapper objectMapper;

    @Test
    void manualSnapshotLatestSnapshotReplayAndMismatchDetectionWork() throws Exception {
        Fixture fixture = fixture();
        TestSocket editor = joinedEditor(fixture, "snapshot-session");
        submitAck(editor, fixture.roomId().toString(), "snap-insert", 1, 0, "TEXT_INSERT", Map.of("position", 0, "text", "hello"));

        Map<String, Object> snapshot = restTemplate.postForObject(baseUrl + "/api/v1/rooms/" + fixture.roomId() + "/snapshots",
                Map.of(), Map.class);
        assertThat(snapshot).containsEntry("revision", 1).containsEntry("contentText", "hello");
        Map<String, Object> latest = restTemplate.getForObject(baseUrl + "/api/v1/rooms/" + fixture.roomId() + "/snapshots/latest", Map.class);
        assertThat(latest).containsEntry("id", snapshot.get("id"));

        submitAck(editor, fixture.roomId().toString(), "snap-tail", 2, 1, "TEXT_INSERT", Map.of("position", 5, "text", "!"));
        Map<String, Object> replay = restTemplate.postForObject(baseUrl + "/api/v1/rooms/" + fixture.roomId() + "/snapshots/replay",
                Map.of(), Map.class);
        assertThat(replay).containsEntry("operationsReplayed", 1).containsEntry("replayEquivalent", true);
        assertThat(documentState(fixture.roomId().toString())).containsEntry("contentText", "hello!");
        assertThat(jdbcTemplate.queryForObject("select count(*) from document_state_rebuild_runs where room_id = ?", Integer.class, fixture.roomId()))
                .isGreaterThanOrEqualTo(1);

        jdbcTemplate.update("update document_snapshots set content_text = 'corrupt' where id = ?::uuid", snapshot.get("id").toString());
        ResponseEntity<Map> mismatch = restTemplate.postForEntity(baseUrl + "/api/v1/rooms/" + fixture.roomId() + "/snapshots/replay",
                Map.of(), Map.class);
        assertThat(mismatch.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        editor.close();
    }

    private TestSocket joinedEditor(Fixture fixture, String session) throws Exception {
        TestSocket editor = TestSocket.connect(websocketUri(), fixture.editorId(), "editor", session, objectMapper);
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
}
