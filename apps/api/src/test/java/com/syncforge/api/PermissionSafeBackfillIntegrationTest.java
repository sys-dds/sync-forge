package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncforge.api.resume.application.RoomBackfillService;
import com.syncforge.api.shared.ForbiddenException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PermissionSafeBackfillIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    RoomBackfillService roomBackfillService;

    @Test
    void authorizedBackfillReturnsOrderedRoomEventsOnlyForRequestedRoom() throws Exception {
        Fixture roomA = fixture();
        Fixture roomB = fixture();
        TestSocket viewer = TestSocket.connect(websocketUri(), roomA.viewerId(), "viewer-device", "viewer-session", objectMapper);
        viewer.send(Map.of("type", "JOIN_ROOM", "messageId", "join", "roomId", roomA.roomId().toString(), "payload", Map.of()));
        String token = payload(viewer.nextOfType("JOINED_ROOM")).get("resumeToken").toString();
        viewer.close();

        TestSocket editorA = joinLegacy(roomA.editorId(), roomA.roomId().toString(), "editor-a");
        editorA.send(operation(roomA.roomId().toString(), "room-a-1", 1, 0, "a"));
        editorA.nextOfType("OPERATION_ACK");
        editorA.send(operation(roomA.roomId().toString(), "room-a-2", 2, 1, "b"));
        editorA.nextOfType("OPERATION_ACK");
        TestSocket editorB = joinLegacy(roomB.editorId(), roomB.roomId().toString(), "editor-b");
        editorB.send(operation(roomB.roomId().toString(), "room-b-secret", 1, 0, "secret"));
        editorB.nextOfType("OPERATION_ACK");

        TestSocket resumed = TestSocket.connect(websocketUri(), roomA.viewerId(), "viewer-device-2", "viewer-session", objectMapper);
        resumed.send(resume(roomA.roomId().toString(), token));

        resumed.nextOfType("ROOM_RESUMED");
        List<Map<String, Object>> events = events(resumed.nextOfType("ROOM_BACKFILL"));
        assertThat(events).extracting(event -> event.get("roomSeq")).containsExactly(1, 2);
        assertThat(events).extracting(event -> event.get("operationId")).containsExactly("room-a-1", "room-a-2");
        assertThat(events).extracting(event -> event.get("operationId")).doesNotContain("room-b-secret");

        editorA.close();
        editorB.close();
        resumed.close();
    }

    @Test
    void removedMemberCannotBackfillAndErrorContainsNoProtectedPayload() throws Exception {
        Fixture fixture = fixture();
        TestSocket viewer = TestSocket.connect(websocketUri(), fixture.viewerId(), "removed-device", "removed-session", objectMapper);
        viewer.send(Map.of("type", "JOIN_ROOM", "messageId", "join", "roomId", fixture.roomId().toString(), "payload", Map.of()));
        String token = payload(viewer.nextOfType("JOINED_ROOM")).get("resumeToken").toString();
        viewer.close();

        jdbcTemplate.update("""
                update room_memberships
                set status = 'REMOVED'
                where room_id = ? and user_id = ?
                """, fixture.roomId(), fixture.viewerId());

        assertThatThrownBy(() -> roomBackfillService.backfill(fixture.roomId(), fixture.viewerId(), "removed-session", 0))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("view room");

        TestSocket resumed = TestSocket.connect(websocketUri(), fixture.viewerId(), "removed-device-2", "removed-session", objectMapper);
        resumed.send(resume(fixture.roomId().toString(), token));
        Map<String, Object> error = payload(resumed.nextOfType("ERROR"));

        assertThat(error).containsEntry("code", "ROOM_ACCESS_DENIED");
        assertThat(error).doesNotContainKey("events");
        assertThat(error).doesNotContainKey("operation");
        assertThat(error).doesNotContainKey("documentState");
        assertThat(countBackfillRequests(fixture.roomId())).isZero();

        resumed.close();
    }

    @Test
    void wrongRoomBackfillIsRejectedWithoutCrossRoomPayload() throws Exception {
        Fixture source = fixture();
        Fixture other = fixture();
        TestSocket viewer = TestSocket.connect(websocketUri(), source.viewerId(), "wrong-device", "wrong-session", objectMapper);
        viewer.send(Map.of("type", "JOIN_ROOM", "messageId", "join", "roomId", source.roomId().toString(), "payload", Map.of()));
        String token = payload(viewer.nextOfType("JOINED_ROOM")).get("resumeToken").toString();
        viewer.close();

        TestSocket wrongRoom = TestSocket.connect(websocketUri(), source.viewerId(), "wrong-device-2", "wrong-session", objectMapper);
        wrongRoom.send(resume(other.roomId().toString(), token));
        Map<String, Object> error = payload(wrongRoom.nextOfType("ERROR"));

        assertThat(error).containsEntry("code", "INVALID_RESUME_TOKEN");
        assertThat(error).doesNotContainKey("events");
        assertThat(error).doesNotContainKey("operation");
        assertThat(error).doesNotContainKey("documentState");

        wrongRoom.close();
    }

    private TestSocket joinLegacy(java.util.UUID userId, String roomId, String session) throws Exception {
        TestSocket socket = TestSocket.connect(websocketUri(), userId, session + "-device", session, objectMapper);
        socket.send(Map.of("type", "JOIN_ROOM", "messageId", "join-" + session, "roomId", roomId, "payload", Map.of()));
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

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> events(Map<String, Object> envelope) {
        return (List<Map<String, Object>>) payload(envelope).get("events");
    }

    private int countBackfillRequests(java.util.UUID roomId) {
        return jdbcTemplate.queryForObject("select count(*) from room_backfill_requests where room_id = ?", Integer.class, roomId);
    }
}
