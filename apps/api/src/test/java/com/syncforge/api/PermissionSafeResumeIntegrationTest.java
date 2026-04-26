package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PermissionSafeResumeIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    ObjectMapper objectMapper;

    @Test
    void userCanResumeWhileStillMember() throws Exception {
        Fixture fixture = fixture();
        TestSocket first = TestSocket.connect(websocketUri(), fixture.viewerId(), "viewer-device", "viewer-session", objectMapper);
        first.send(Map.of("type", "JOIN_ROOM", "messageId", "join", "roomId", fixture.roomId().toString(), "payload", Map.of()));
        String token = payload(first.nextOfType("JOINED_ROOM")).get("resumeToken").toString();
        first.close();

        TestSocket resumed = TestSocket.connect(websocketUri(), fixture.viewerId(), "viewer-device-2", "viewer-session", objectMapper);
        resumed.send(resumeMessage(fixture.roomId().toString(), token));

        assertThat(resumed.nextOfType("ROOM_RESUMED")).containsEntry("type", "ROOM_RESUMED");
        assertThat(resumed.nextOfType("ROOM_BACKFILL")).containsEntry("type", "ROOM_BACKFILL");

        resumed.close();
    }

    @Test
    void removedMemberCannotResumeWithValidStaleTokenOrPriorNegotiation() throws Exception {
        Fixture fixture = fixture();
        TestSocket first = TestSocket.connect(websocketUri(), fixture.editorId(), "removed-device", "removed-session", objectMapper);
        first.send(Map.of(
                "type", "JOIN_ROOM",
                "messageId", "join",
                "roomId", fixture.roomId().toString(),
                "payload", Map.of(
                        "protocolVersion", 2,
                        "clientId", "removed-client",
                        "capabilities", List.of("OPERATIONS", "RESUME", "BACKFILL", "SNAPSHOT"))));
        String token = payload(first.nextOfType("JOINED_ROOM")).get("resumeToken").toString();
        String originalConnectionId = first.nextOfType("PROTOCOL_NEGOTIATED").get("connectionId").toString();
        first.close();

        jdbcTemplate.update("""
                update room_memberships
                set status = 'REMOVED'
                where room_id = ? and user_id = ?
                """, fixture.roomId(), fixture.editorId());

        TestSocket resumed = TestSocket.connect(websocketUri(), fixture.editorId(), "removed-device-2", "removed-session", objectMapper);
        resumed.send(resumeMessage(fixture.roomId().toString(), token));

        Map<String, Object> error = payload(resumed.nextOfType("ERROR"));
        assertThat(error).containsEntry("code", "ROOM_ACCESS_DENIED");
        assertThat(error).doesNotContainKey("documentState");
        assertThat(error).doesNotContainKey("events");
        assertThat(error).doesNotContainKey("contentText");
        assertThat(countPresentConnections(fixture.roomId(), fixture.editorId())).isZero();
        assertThat(countConnectedSessions(fixture.roomId(), fixture.editorId())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from websocket_protocol_sessions where room_id = ? and user_id = ? and status = 'NEGOTIATED' and connection_id <> ?",
                Integer.class,
                fixture.roomId(),
                fixture.editorId(),
                originalConnectionId)).isZero();

        resumed.close();
    }

    private Map<String, Object> resumeMessage(String roomId, String token) {
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

    private int countPresentConnections(java.util.UUID roomId, java.util.UUID userId) {
        return jdbcTemplate.queryForObject("""
                select count(*)
                from room_presence_connections
                where room_id = ? and user_id = ? and status = 'PRESENT'
                """, Integer.class, roomId, userId);
    }

    private int countConnectedSessions(java.util.UUID roomId, java.util.UUID userId) {
        return jdbcTemplate.queryForObject("""
                select count(*)
                from room_connection_sessions
                where room_id = ? and user_id = ? and status = 'CONNECTED'
                """, Integer.class, roomId, userId);
    }
}
