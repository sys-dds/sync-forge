package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class Sync016To025FunctionalIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    ObjectMapper objectMapper;

    @Test
    void endToEndStateSnapshotResumeBackfillAndConflictTraceWorkTogether() throws Exception {
        Fixture fixture = fixture();
        TestSocket userA = TestSocket.connect(websocketUri(), fixture.editorId(), "a-device", "a-session", objectMapper);
        TestSocket userB = TestSocket.connect(websocketUri(), fixture.ownerId(), "b-device", "b-session", objectMapper);
        join(userA, fixture.roomId().toString());
        Map<String, Object> userBJoin = join(userB, fixture.roomId().toString());
        String userBToken = payload(userBJoin).get("resumeToken").toString();

        submitAck(userA, fixture.roomId().toString(), "e2e-1", 1, 0, "TEXT_INSERT", Map.of("position", 0, "text", "hello"));
        userB.send(Map.of("type", "ACK_ROOM_EVENT", "messageId", "ack", "roomId", fixture.roomId().toString(),
                "payload", Map.of("roomSeq", 1, "resumeToken", userBToken)));
        userB.nextOfType("ROOM_EVENT_ACKED");
        userB.close();

        submitAck(userA, fixture.roomId().toString(), "e2e-2", 2, 1, "TEXT_INSERT", Map.of("position", 5, "text", " world"));
        submitAck(userA, fixture.roomId().toString(), "e2e-3", 3, 2, "TEXT_REPLACE", Map.of("position", 0, "length", 5, "text", "hi"));
        restTemplate.postForObject(baseUrl + "/api/v1/rooms/" + fixture.roomId() + "/snapshots", Map.of(), Map.class);

        TestSocket resumedB = TestSocket.connect(websocketUri(), fixture.ownerId(), "b-device-2", "b-session", objectMapper);
        resumedB.send(Map.of("type", "RESUME_ROOM", "messageId", "resume", "roomId", fixture.roomId().toString(),
                "payload", Map.of("resumeToken", userBToken, "lastSeenRoomSeq", 1)));
        resumedB.nextOfType("ROOM_RESUMED");
        List<Map<String, Object>> events = events(resumedB.nextOfType("ROOM_BACKFILL"));
        assertThat(events).extracting(event -> event.get("roomSeq")).containsExactly(2, 3);

        Map<String, Object> replay = restTemplate.postForObject(baseUrl + "/api/v1/rooms/" + fixture.roomId() + "/document-state/rebuild",
                Map.of(), Map.class);
        assertThat(replay).containsEntry("replayEquivalent", true);
        assertThat(documentState(fixture.roomId().toString())).containsEntry("contentText", "hi world");

        userA.send(operationMessage("e2e-conflict", 4, 1, "TEXT_REPLACE", Map.of("position", 0, "length", 2, "text", "yo"), fixture.roomId().toString()));
        assertThat(payload(userA.nextOfType("OPERATION_NACK"))).containsEntry("code", "CONFLICT_REQUIRES_RESYNC");
        assertThat(jdbcTemplate.queryForObject("select count(*) from room_conflict_resolution_traces where operation_id = 'e2e-conflict'", Integer.class))
                .isEqualTo(1);
        userA.close();
        resumedB.close();
    }

    private Map<String, Object> join(TestSocket socket, String roomId) throws Exception {
        socket.send(Map.of("type", "JOIN_ROOM", "messageId", "join", "roomId", roomId, "payload", Map.of()));
        Map<String, Object> joined = socket.nextOfType("JOINED_ROOM");
        socket.drain();
        return joined;
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> documentState(String roomId) {
        return restTemplate.getForObject(baseUrl + "/api/v1/rooms/" + roomId + "/document-state", Map.class);
    }
}
