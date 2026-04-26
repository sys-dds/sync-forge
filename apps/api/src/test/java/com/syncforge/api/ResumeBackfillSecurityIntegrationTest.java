package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ResumeBackfillSecurityIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    ObjectMapper objectMapper;

    @Test
    void resumeBackfillSecurityMatrixIsEnforced() throws Exception {
        Fixture fixture = fixture();
        Fixture other = fixture();
        TestSocket editor = TestSocket.connect(websocketUri(), fixture.editorId(), "editor", "editor-session", objectMapper);
        TestSocket viewer = TestSocket.connect(websocketUri(), fixture.viewerId(), "viewer", "viewer-session", objectMapper);
        String viewerToken = payload(join(viewer, fixture.roomId().toString())).get("resumeToken").toString();
        join(editor, fixture.roomId().toString());
        viewer.drain();
        editor.drain();

        submitAck(editor, fixture.roomId().toString(), "resume-sec-1", 1, 0, "TEXT_INSERT", Map.of("position", 0, "text", "a"));
        submitAck(editor, fixture.roomId().toString(), "resume-sec-2", 2, 1, "TEXT_INSERT", Map.of("position", 1, "text", "b"));
        submitAck(editor, fixture.roomId().toString(), "resume-sec-2", 99, 1, "TEXT_INSERT", Map.of("position", 1, "text", "b"));
        viewer.close();

        TestSocket valid = TestSocket.connect(websocketUri(), fixture.viewerId(), "viewer-valid", "viewer-session", objectMapper);
        valid.send(resumeMessage("valid", fixture.roomId().toString(), viewerToken, 0));
        assertThat(valid.nextOfType("ROOM_RESUMED")).containsEntry("messageId", "valid");
        List<Map<String, Object>> validEvents = events(valid.nextOfType("ROOM_BACKFILL"));
        assertThat(validEvents).extracting(event -> event.get("roomSeq")).containsExactly(1, 2);
        assertThat(validEvents).extracting(event -> event.get("operationId")).containsExactly("resume-sec-1", "resume-sec-2");
        valid.close();

        assertResumeError(fixture.viewerId(), "wrong-room-session", other.roomId().toString(), viewerToken, "wrong-room");
        assertResumeError(fixture.ownerId(), "viewer-session", fixture.roomId().toString(), viewerToken, "wrong-user");
        assertResumeError(fixture.viewerId(), "wrong-session", fixture.roomId().toString(), viewerToken, "wrong-session");
        assertResumeError(fixture.outsiderId(), "viewer-session", fixture.roomId().toString(), viewerToken, "non-member");

        jdbcTemplate.update("update room_resume_tokens set revoked_at = now() where token_hash is not null");
        assertResumeError(fixture.viewerId(), "viewer-session", fixture.roomId().toString(), viewerToken, "revoked");

        TestSocket owner = TestSocket.connect(websocketUri(), fixture.ownerId(), "owner", "owner-session", objectMapper);
        String ownerToken = payload(join(owner, fixture.roomId().toString())).get("resumeToken").toString();
        owner.close();
        jdbcTemplate.update("update room_resume_tokens set issued_at = ?, expires_at = ? where token_hash is not null",
                OffsetDateTime.now().minusHours(2), OffsetDateTime.now().minusHours(1));
        assertResumeError(fixture.ownerId(), "owner-session", fixture.roomId().toString(), ownerToken, "expired");

        TestSocket removed = TestSocket.connect(websocketUri(), fixture.editorId(), "removed", "removed-session", objectMapper);
        String removedToken = payload(join(removed, fixture.roomId().toString())).get("resumeToken").toString();
        removed.close();
        jdbcTemplate.update("update room_memberships set status = 'REMOVED' where room_id = ? and user_id = ?",
                fixture.roomId(), fixture.editorId());
        assertResumeError(fixture.editorId(), "removed-session", fixture.roomId().toString(), removedToken, "removed");

        TestSocket farOwner = TestSocket.connect(websocketUri(), fixture.ownerId(), "far", "far-session", objectMapper);
        String farToken = payload(join(farOwner, fixture.roomId().toString())).get("resumeToken").toString();
        for (int index = 3; index <= 105; index++) {
            submitAck(farOwner, fixture.roomId().toString(), "far-" + index, index, index - 1, "NOOP", Map.of());
        }
        farOwner.close();
        TestSocket farBehind = TestSocket.connect(websocketUri(), fixture.ownerId(), "far-2", "far-session", objectMapper);
        farBehind.send(resumeMessage("too-far", fixture.roomId().toString(), farToken, 0));
        farBehind.nextOfType("ROOM_RESUMED");
        assertThat(payload(farBehind.nextOfType("RESYNC_REQUIRED"))).containsKey("documentState");
        farBehind.close();

        editor.close();
    }

    private void assertResumeError(java.util.UUID userId, String sessionId, String roomId, String token, String messageId) throws Exception {
        TestSocket socket = TestSocket.connect(websocketUri(), userId, "device-" + messageId, sessionId, objectMapper);
        socket.send(resumeMessage(messageId, roomId, token, 0));
        assertThat(socket.nextOfType("ERROR")).containsEntry("messageId", messageId);
        socket.close();
    }

    private Map<String, Object> join(TestSocket socket, String roomId) throws Exception {
        socket.send(Map.of("type", "JOIN_ROOM", "messageId", "join-" + roomId, "roomId", roomId, "payload", Map.of()));
        return socket.nextOfType("JOINED_ROOM");
    }

    private void submitAck(TestSocket socket, String roomId, String operationId, long clientSeq, long baseRevision, String type, Map<String, Object> operation) throws Exception {
        socket.send(Map.of("type", "SUBMIT_OPERATION", "messageId", operationId, "roomId", roomId,
                "payload", Map.of("operationId", operationId, "clientSeq", clientSeq, "baseRevision", baseRevision,
                        "operationType", type, "operation", operation)));
        socket.nextOfType("OPERATION_ACK");
    }

    private Map<String, Object> resumeMessage(String messageId, String roomId, String token, long lastSeenRoomSeq) {
        return Map.of("type", "RESUME_ROOM", "messageId", messageId, "roomId", roomId,
                "payload", Map.of("resumeToken", token, "lastSeenRoomSeq", lastSeenRoomSeq));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("payload");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> events(Map<String, Object> envelope) {
        return (List<Map<String, Object>>) payload(envelope).get("events");
    }
}
