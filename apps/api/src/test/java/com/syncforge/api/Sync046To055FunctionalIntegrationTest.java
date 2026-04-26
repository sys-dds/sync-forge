package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class Sync046To055FunctionalIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    ObjectMapper objectMapper;

    @Test
    void legacyAndV2ClientsRemainCompatibleWhileRemovedClientCannotResumeBackfillOrReadState() throws Exception {
        Fixture fixture = fixture();
        TestSocket legacyEditor = TestSocket.connect(websocketUri(), fixture.editorId(), "legacy-device", "legacy-session", objectMapper);
        legacyEditor.send(Map.of("type", "JOIN_ROOM", "messageId", "legacy-join", "roomId", fixture.roomId().toString(), "payload", Map.of()));
        legacyEditor.nextOfType("JOINED_ROOM");

        V2Join v2Viewer = joinV2(fixture.viewerId(), fixture.roomId().toString(), "v2-viewer",
                List.of("PRESENCE", "RESUME", "BACKFILL", "SNAPSHOT"));
        String viewerToken = payload(v2Viewer.lastNegotiationJoin()).get("resumeToken").toString();

        legacyEditor.send(operation(fixture.roomId().toString(), "sync046-a", 1, 0, 0, "A"));
        legacyEditor.nextOfType("OPERATION_ACK");

        v2Viewer.socket().send(Map.of("type", "GET_DOCUMENT_STATE", "messageId", "state-ok", "roomId", fixture.roomId().toString(), "payload", Map.of()));
        assertThat(payload(v2Viewer.socket().nextOfType("DOCUMENT_STATE"))).containsEntry("contentText", "A");

        jdbcTemplate.update("""
                update room_memberships
                set status = 'REMOVED'
                where room_id = ? and user_id = ?
                """, fixture.roomId(), fixture.viewerId());

        v2Viewer.socket().send(Map.of("type", "GET_DOCUMENT_STATE", "messageId", "state-blocked", "roomId", fixture.roomId().toString(), "payload", Map.of()));
        Map<String, Object> stateError = payload(v2Viewer.socket().nextOfType("ERROR"));
        assertThat(stateError).containsEntry("code", "ROOM_ACCESS_DENIED");
        assertThat(stateError).doesNotContainKey("contentText");
        v2Viewer.socket().close();

        TestSocket removedResume = TestSocket.connect(websocketUri(), fixture.viewerId(), "viewer-resume-device", "v2-viewer-session", objectMapper);
        removedResume.send(resume(fixture.roomId().toString(), viewerToken));
        Map<String, Object> resumeError = payload(removedResume.nextOfType("ERROR"));
        assertThat(resumeError).containsEntry("code", "ROOM_ACCESS_DENIED");
        assertThat(resumeError).doesNotContainKey("events");
        assertThat(resumeError).doesNotContainKey("documentState");

        legacyEditor.send(operation(fixture.roomId().toString(), "sync046-b", 2, 1, 1, "B"));
        legacyEditor.nextOfType("OPERATION_ACK");
        legacyEditor.send(Map.of("type", "GET_DOCUMENT_STATE", "messageId", "final-state", "roomId", fixture.roomId().toString(), "payload", Map.of()));
        assertThat(payload(legacyEditor.nextOfType("DOCUMENT_STATE"))).containsEntry("contentText", "AB");

        legacyEditor.close();
        removedResume.close();
    }

    private V2Join joinV2(java.util.UUID userId, String roomId, String clientId, List<String> capabilities) throws Exception {
        TestSocket socket = TestSocket.connect(websocketUri(), userId, clientId + "-device", clientId + "-session", objectMapper);
        socket.send(Map.of(
                "type", "JOIN_ROOM",
                "messageId", "join-" + clientId,
                "roomId", roomId,
                "payload", Map.of(
                        "protocolVersion", 2,
                        "clientId", clientId,
                        "capabilities", capabilities)));
        Map<String, Object> joined = socket.nextOfType("JOINED_ROOM");
        assertThat(payload(socket.nextOfType("PROTOCOL_NEGOTIATED"))).containsEntry("negotiatedProtocolVersion", 2);
        return new V2Join(socket, joined);
    }

    private Map<String, Object> operation(String roomId, String operationId, long clientSeq, long baseRevision, int position, String text) {
        return Map.of(
                "type", "SUBMIT_OPERATION",
                "messageId", operationId,
                "roomId", roomId,
                "payload", Map.of(
                        "operationId", operationId,
                        "clientSeq", clientSeq,
                        "baseRevision", baseRevision,
                        "operationType", "TEXT_INSERT",
                        "operation", Map.of("position", position, "text", text)));
    }

    private Map<String, Object> resume(String roomId, String token) {
        return Map.of(
                "type", "RESUME_ROOM",
                "messageId", "resume",
                "roomId", roomId,
                "payload", Map.of(
                        "protocolVersion", 2,
                        "capabilities", List.of("RESUME", "BACKFILL", "SNAPSHOT"),
                        "resumeToken", token,
                        "lastSeenRoomSeq", 0));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("payload");
    }

    private record V2Join(TestSocket socket, Map<String, Object> lastNegotiationJoin) {
    }
}
