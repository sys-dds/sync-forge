package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ReconnectResumeIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    ObjectMapper objectMapper;

    @Test
    void resumeTokenOffsetBackfillExpiryResyncAndMembershipChecksWork() throws Exception {
        Fixture fixture = fixture();
        TestSocket editor = TestSocket.connect(websocketUri(), fixture.editorId(), "editor", "resume-editor", objectMapper);
        TestSocket viewer = TestSocket.connect(websocketUri(), fixture.viewerId(), "viewer", "resume-viewer", objectMapper);
        join(editor, fixture.roomId().toString());
        Map<String, Object> viewerJoin = join(viewer, fixture.roomId().toString());
        String resumeToken = payload(viewerJoin).get("resumeToken").toString();
        viewer.drain();

        submitAck(editor, fixture.roomId().toString(), "resume-1", 1, 0, "TEXT_INSERT", Map.of("position", 0, "text", "a"));
        viewer.send(Map.of("type", "ACK_ROOM_EVENT", "messageId", "ack-1", "roomId", fixture.roomId().toString(),
                "payload", Map.of("roomSeq", 1, "resumeToken", resumeToken)));
        assertThat(payload(viewer.nextOfType("ROOM_EVENT_ACKED"))).containsEntry("roomSeq", 1);
        viewer.close();

        submitAck(editor, fixture.roomId().toString(), "resume-2", 2, 1, "TEXT_INSERT", Map.of("position", 1, "text", "b"));
        submitAck(editor, fixture.roomId().toString(), "resume-3", 3, 2, "TEXT_INSERT", Map.of("position", 2, "text", "c"));

        TestSocket resumed = TestSocket.connect(websocketUri(), fixture.viewerId(), "viewer-2", "resume-viewer", objectMapper);
        resumed.send(Map.of("type", "RESUME_ROOM", "messageId", "resume", "roomId", fixture.roomId().toString(),
                "payload", Map.of("resumeToken", resumeToken, "lastSeenRoomSeq", 1)));
        resumed.nextOfType("ROOM_RESUMED");
        Map<String, Object> backfill = resumed.nextOfType("ROOM_BACKFILL");
        List<Map<String, Object>> events = events(backfill);
        assertThat(events).extracting(event -> event.get("roomSeq")).containsExactly(2, 3);

        jdbcTemplate.update("update room_resume_tokens set issued_at = ?, expires_at = ? where token_hash is not null",
                OffsetDateTime.now().minusHours(2), OffsetDateTime.now().minusHours(1));
        TestSocket expired = TestSocket.connect(websocketUri(), fixture.viewerId(), "viewer-3", "resume-viewer", objectMapper);
        expired.send(Map.of("type", "RESUME_ROOM", "messageId", "expired", "roomId", fixture.roomId().toString(),
                "payload", Map.of("resumeToken", resumeToken)));
        assertThat(expired.nextOfType("ERROR").get("type")).isEqualTo("ERROR");
        expired.close();

        TestSocket owner = TestSocket.connect(websocketUri(), fixture.ownerId(), "owner", "owner-resync", objectMapper);
        Map<String, Object> ownerJoin = join(owner, fixture.roomId().toString());
        String ownerToken = payload(ownerJoin).get("resumeToken").toString();
        for (int index = 4; index <= 105; index++) {
            submitAck(editor, fixture.roomId().toString(), "resume-" + index, index, index - 1, "NOOP", Map.of());
        }
        owner.close();
        TestSocket farBehind = TestSocket.connect(websocketUri(), fixture.ownerId(), "owner-2", "owner-resync", objectMapper);
        farBehind.send(Map.of("type", "RESUME_ROOM", "messageId", "too-far", "roomId", fixture.roomId().toString(),
                "payload", Map.of("resumeToken", ownerToken, "lastSeenRoomSeq", 1)));
        farBehind.nextOfType("ROOM_RESUMED");
        assertThat(farBehind.nextOfType("RESYNC_REQUIRED").get("type")).isEqualTo("RESYNC_REQUIRED");

        TestSocket nonMember = TestSocket.connect(websocketUri(), fixture.outsiderId(), "outsider", "resume-viewer", objectMapper);
        nonMember.send(Map.of("type", "RESUME_ROOM", "messageId", "non-member", "roomId", fixture.roomId().toString(),
                "payload", Map.of("resumeToken", ownerToken)));
        assertThat(nonMember.nextOfType("ERROR").get("type")).isEqualTo("ERROR");
        editor.close();
        resumed.close();
        farBehind.close();
        nonMember.close();
    }

    private Map<String, Object> join(TestSocket socket, String roomId) throws Exception {
        socket.send(Map.of("type", "JOIN_ROOM", "messageId", "join", "roomId", roomId, "payload", Map.of()));
        return socket.nextOfType("JOINED_ROOM");
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
    private Map<String, Object> payload(Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("payload");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> events(Map<String, Object> envelope) {
        return (List<Map<String, Object>>) payload(envelope).get("events");
    }
}
