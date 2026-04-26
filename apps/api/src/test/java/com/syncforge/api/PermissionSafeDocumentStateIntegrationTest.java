package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class PermissionSafeDocumentStateIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    ObjectMapper objectMapper;

    @Test
    void websocketDocumentStateRechecksCurrentPermissionAndDoesNotLeakContent() throws Exception {
        Fixture fixture = fixture();
        TestSocket editor = joinV2(fixture.editorId(), fixture.roomId().toString(), "editor", List.of("OPERATIONS", "SNAPSHOT"));
        editor.send(operation(fixture.roomId().toString(), "state-secret", 1, 0, "secret"));
        editor.nextOfType("OPERATION_ACK");

        TestSocket viewer = joinV2(fixture.viewerId(), fixture.roomId().toString(), "viewer", List.of("SNAPSHOT"));
        viewer.send(Map.of("type", "GET_DOCUMENT_STATE", "messageId", "state-ok", "roomId", fixture.roomId().toString(), "payload", Map.of()));
        assertThat(payload(viewer.nextOfType("DOCUMENT_STATE"))).containsEntry("contentText", "secret");

        removeMember(fixture.roomId(), fixture.viewerId());
        viewer.send(Map.of("type", "GET_DOCUMENT_STATE", "messageId", "state-blocked", "roomId", fixture.roomId().toString(), "payload", Map.of()));
        Map<String, Object> error = payload(viewer.nextOfType("ERROR"));
        assertThat(error).containsEntry("code", "ROOM_ACCESS_DENIED");
        assertThat(error).doesNotContainKey("contentText");
        assertThat(error).doesNotContainEntry("message", "secret");

        editor.close();
        viewer.close();
    }

    @Test
    void httpDocumentStateAllowsMemberAndRejectsRemovedMemberWithoutContent() throws Exception {
        Fixture fixture = fixture();
        TestSocket editor = joinV2(fixture.editorId(), fixture.roomId().toString(), "editor", List.of("OPERATIONS"));
        editor.send(operation(fixture.roomId().toString(), "http-secret", 1, 0, "classified"));
        editor.nextOfType("OPERATION_ACK");

        ResponseEntity<Map> allowed = restTemplate.getForEntity(
                baseUrl + "/api/v1/rooms/" + fixture.roomId() + "/document-state?userId=" + fixture.viewerId(),
                Map.class);
        assertThat(allowed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(allowed.getBody()).containsEntry("contentText", "classified");

        removeMember(fixture.roomId(), fixture.viewerId());
        ResponseEntity<Map> rejected = restTemplate.getForEntity(
                baseUrl + "/api/v1/rooms/" + fixture.roomId() + "/document-state?userId=" + fixture.viewerId(),
                Map.class);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rejected.getBody()).containsEntry("code", "ROOM_ACCESS_DENIED");
        assertThat(rejected.getBody()).doesNotContainKey("contentText");
        assertThat(rejected.getBody()).doesNotContainEntry("message", "classified");

        editor.close();
    }

    @Test
    void snapshotFetchRechecksCurrentPermissionAndDoesNotLeakContent() throws Exception {
        Fixture fixture = fixture();
        TestSocket editor = joinV2(fixture.editorId(), fixture.roomId().toString(), "editor", List.of("OPERATIONS"));
        editor.send(operation(fixture.roomId().toString(), "snapshot-secret", 1, 0, "snapshot-secret"));
        editor.nextOfType("OPERATION_ACK");

        ResponseEntity<Map> created = restTemplate.postForEntity(
                baseUrl + "/api/v1/rooms/" + fixture.roomId() + "/snapshots?userId=" + fixture.ownerId(),
                null,
                Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> allowed = restTemplate.getForEntity(
                baseUrl + "/api/v1/rooms/" + fixture.roomId() + "/snapshots/latest?userId=" + fixture.viewerId(),
                Map.class);
        assertThat(allowed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(allowed.getBody()).containsEntry("contentText", "snapshot-secret");

        removeMember(fixture.roomId(), fixture.viewerId());
        ResponseEntity<Map> rejected = restTemplate.getForEntity(
                baseUrl + "/api/v1/rooms/" + fixture.roomId() + "/snapshots/latest?userId=" + fixture.viewerId(),
                Map.class);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rejected.getBody()).containsEntry("code", "ROOM_ACCESS_DENIED");
        assertThat(rejected.getBody()).doesNotContainKey("contentText");
        assertThat(rejected.getBody()).doesNotContainEntry("message", "snapshot-secret");

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

    private void removeMember(java.util.UUID roomId, java.util.UUID userId) {
        jdbcTemplate.update("""
                update room_memberships
                set status = 'REMOVED'
                where room_id = ? and user_id = ?
                """, roomId, userId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("payload");
    }
}
