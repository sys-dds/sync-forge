package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.socket.WebSocketHttpHeaders;

class WebSocketGatewayIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void joinRoomSucceedsForOwnerEditorAndViewer() throws Exception {
        Fixture fixture = fixture();

        assertJoinSucceeds(fixture.ownerId(), fixture.roomId());
        assertJoinSucceeds(fixture.editorId(), fixture.roomId());
        assertJoinSucceeds(fixture.viewerId(), fixture.roomId());
    }

    @Test
    void joinRoomRejectsNonMemberMissingUserAndUnknownRoom() throws Exception {
        Fixture fixture = fixture();

        TestSocket nonMember = connect(fixture.outsiderId(), null, null);
        nonMember.send(Map.of("type", "JOIN_ROOM", "messageId", "non-member", "roomId", fixture.roomId().toString(), "payload", Map.of()));
        Map<String, Object> nonMemberReply = nonMember.nextOfType("ERROR");
        assertThat(nonMemberReply).containsEntry("type", "ERROR");
        assertThat(payload(nonMemberReply)).containsEntry("code", "ROOM_ACCESS_DENIED");
        nonMember.close();

        TestSocket missingUser = connectWithoutUser();
        missingUser.send(Map.of("type", "JOIN_ROOM", "messageId", "missing-user", "roomId", fixture.roomId().toString(), "payload", Map.of()));
        Map<String, Object> missingUserReply = missingUser.nextOfType("ERROR");
        assertThat(missingUserReply).containsEntry("type", "ERROR");
        assertThat(payload(missingUserReply)).containsEntry("code", "MISSING_USER");
        missingUser.close();

        TestSocket unknownRoom = connect(fixture.ownerId(), null, null);
        unknownRoom.send(Map.of("type", "JOIN_ROOM", "messageId", "unknown-room", "roomId", UUID.randomUUID().toString(), "payload", Map.of()));
        Map<String, Object> unknownRoomReply = unknownRoom.nextOfType("ERROR");
        assertThat(unknownRoomReply).containsEntry("type", "ERROR");
        assertThat(payload(unknownRoomReply)).containsEntry("code", "ROOM_NOT_FOUND");
        unknownRoom.close();
    }

    @Test
    void pingLeaveSocketCloseAndInvalidJsonBehaveCorrectly() throws Exception {
        Fixture fixture = fixture();
        TestSocket client = connect(fixture.ownerId(), "device-a", "session-a");
        client.send(Map.of("type", "JOIN_ROOM", "messageId", "join", "roomId", fixture.roomId().toString(), "payload", Map.of()));
        Map<String, Object> joined = client.nextOfType("JOINED_ROOM");
        String connectionId = joined.get("connectionId").toString();

        client.send(Map.of("type", "PING", "messageId", "ping", "roomId", fixture.roomId().toString(), "payload", Map.of()));
        Map<String, Object> pong = client.nextOfType("PONG");
        assertThat(pong).containsEntry("type", "PONG").containsEntry("connectionId", connectionId);
        Integer pingEvents = jdbcTemplate.queryForObject(
                "select count(*) from room_connection_events where connection_id = ? and event_type = 'PING'",
                Integer.class,
                connectionId);
        assertThat(pingEvents).isEqualTo(1);

        client.send(Map.of("type", "LEAVE_ROOM", "messageId", "leave", "roomId", fixture.roomId().toString(), "payload", Map.of()));
        Map<String, Object> left = client.nextOfType("LEFT_ROOM");
        assertThat(left).containsEntry("type", "LEFT_ROOM");
        String leaveStatus = jdbcTemplate.queryForObject(
                "select status from room_connection_sessions where connection_id = ?",
                String.class,
                connectionId);
        assertThat(leaveStatus).isEqualTo("DISCONNECTED");
        client.close();

        TestSocket closeClient = connect(fixture.ownerId(), "device-b", "session-b");
        closeClient.send(Map.of("type", "JOIN_ROOM", "messageId", "join-close", "roomId", fixture.roomId().toString(), "payload", Map.of()));
        String closeConnectionId = closeClient.nextOfType("JOINED_ROOM").get("connectionId").toString();
        closeClient.close();
        Thread.sleep(250);
        String reason = jdbcTemplate.queryForObject(
                "select disconnect_reason from room_connection_sessions where connection_id = ?",
                String.class,
                closeConnectionId);
        assertThat(reason).isEqualTo("SOCKET_CLOSED");

        TestSocket invalidJson = connect(fixture.ownerId(), null, null);
        invalidJson.sendRaw("{not-json");
        Map<String, Object> error = invalidJson.nextOfType("ERROR");
        assertThat(error).containsEntry("type", "ERROR");
        assertThat(payload(error)).containsEntry("code", "INVALID_JSON");
        invalidJson.close();
    }

    @Test
    void leaveBeforeJoinReturnsError() throws Exception {
        Fixture fixture = fixture();
        TestSocket client = connect(fixture.ownerId(), null, null);
        client.send(Map.of("type", "LEAVE_ROOM", "messageId", "leave-first", "roomId", fixture.roomId().toString(), "payload", Map.of()));
        Map<String, Object> reply = client.nextOfType("ERROR");
        assertThat(reply).containsEntry("type", "ERROR");
        assertThat(payload(reply)).containsEntry("code", "CONNECTION_NOT_JOINED");
        client.close();
    }

    @Test
    void secondJoinOnSameSocketIsRejectedWithoutCreatingAnotherSession() throws Exception {
        Fixture fixture = fixture();
        TestSocket client = connect(fixture.ownerId(), "device-one", "client-session-one");
        client.send(Map.of("type", "JOIN_ROOM", "messageId", "join-one", "roomId", fixture.roomId().toString(), "payload", Map.of()));
        Map<String, Object> joined = client.nextOfType("JOINED_ROOM");
        assertThat(joined).containsEntry("type", "JOINED_ROOM");
        String connectionId = joined.get("connectionId").toString();

        client.send(Map.of("type", "JOIN_ROOM", "messageId", "join-two", "roomId", fixture.roomId().toString(), "payload", Map.of()));
        Map<String, Object> rejected = client.nextOfType("ERROR");
        assertThat(rejected).containsEntry("type", "ERROR");
        assertThat(rejected).containsEntry("connectionId", connectionId);
        assertThat(payload(rejected)).containsEntry("code", "ALREADY_JOINED_ROOM");

        Integer activeSessions = jdbcTemplate.queryForObject("""
                select count(*)
                from room_connection_sessions
                where room_id = ? and user_id = ? and status = 'CONNECTED'
                """, Integer.class, fixture.roomId(), fixture.ownerId());
        assertThat(activeSessions).isEqualTo(1);

        client.send(Map.of("type", "PING", "messageId", "ping-after-reject", "roomId", fixture.roomId().toString(), "payload", Map.of()));
        Map<String, Object> pong = client.nextOfType("PONG");
        assertThat(pong).containsEntry("type", "PONG").containsEntry("connectionId", connectionId);
        client.close();
    }

    private void assertJoinSucceeds(UUID userId, UUID roomId) throws Exception {
        TestSocket client = connect(userId, null, null);
        client.send(Map.of("type", "JOIN_ROOM", "messageId", "join", "roomId", roomId.toString(), "payload", Map.of()));
        Map<String, Object> reply = client.nextOfType("JOINED_ROOM");
        assertThat(reply).containsEntry("type", "JOINED_ROOM").containsEntry("roomId", roomId.toString());
        assertThat(reply.get("connectionId")).isNotNull();
        client.close();
    }

    TestSocket connect(UUID userId, String deviceId, String sessionId) throws Exception {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.add("X-User-Id", userId.toString());
        if (deviceId != null) {
            headers.add("X-Device-Id", deviceId);
        }
        if (sessionId != null) {
            headers.add("X-Session-Id", sessionId);
        }
        return TestSocket.connect(websocketUri(), headers, objectMapper);
    }

    TestSocket connectWithoutUser() throws Exception {
        return TestSocket.connect(websocketUri(), new WebSocketHttpHeaders(), objectMapper);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> payload(Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("payload");
    }

}
