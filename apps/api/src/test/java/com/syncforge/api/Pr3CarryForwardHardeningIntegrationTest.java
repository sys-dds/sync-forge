package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class Pr3CarryForwardHardeningIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    ObjectMapper objectMapper;

    @Test
    void documentStateDriftAndMutationRulesAreObservable() throws Exception {
        Fixture fixture = fixture();
        TestSocket editor = joinedEditor(fixture, "pr3-state-session");

        submitAck(editor, fixture.roomId().toString(), "state-insert", 1, 0, "TEXT_INSERT", Map.of("position", 0, "text", "abc"));
        Map<String, Object> beforeDuplicate = documentState(fixture.roomId().toString());
        submitAck(editor, fixture.roomId().toString(), "state-insert", 1, 0, "TEXT_INSERT", Map.of("position", 0, "text", "abc"));
        assertThat(documentState(fixture.roomId().toString()))
                .containsEntry("contentText", "abc")
                .containsEntry("currentRevision", beforeDuplicate.get("currentRevision"));

        editor.send(operationMessage("state-rejected", 2, 1, "TEXT_DELETE", Map.of("position", 9, "length", 1), fixture.roomId().toString()));
        assertThat(payload(editor.nextOfType("OPERATION_NACK"))).containsEntry("code", "INVALID_OPERATION_PAYLOAD");
        assertThat(documentState(fixture.roomId().toString())).containsEntry("contentText", "abc").containsEntry("currentRevision", 1);

        Map<String, Object> rebuilt = restTemplate.postForObject(baseUrl + "/api/v1/rooms/" + fixture.roomId() + "/document-state/rebuild",
                Map.of(), Map.class);
        assertThat(rebuilt).containsEntry("replayEquivalent", true).containsEntry("contentChecksum", beforeDuplicate.get("contentChecksum"));

        jdbcTemplate.update("update document_live_states set content_text = 'drift', content_checksum = 'not-the-real-checksum' where room_id = ?", fixture.roomId());
        Map<String, Object> driftRepair = restTemplate.postForObject(baseUrl + "/api/v1/rooms/" + fixture.roomId() + "/document-state/rebuild",
                Map.of(), Map.class);
        assertThat(driftRepair).containsEntry("replayEquivalent", false);
        assertThat(documentState(fixture.roomId().toString())).containsEntry("contentText", "abc");
        editor.close();
    }

    @Test
    void otV1EdgeCasesTraceAndBroadcastTransformedPayloads() throws Exception {
        Fixture fixture = fixture();
        TestSocket editor = joinedEditor(fixture, "pr3-ot-editor");
        TestSocket observer = joinedViewer(fixture, "pr3-ot-observer");

        submitAck(editor, fixture.roomId().toString(), "same-pos-existing", 1, 0, "TEXT_INSERT", Map.of("position", 0, "text", "A"));
        Map<String, Object> samePositionAck = submitAck(editor, fixture.roomId().toString(), "same-pos-incoming", 2, 0, "TEXT_INSERT", Map.of("position", 0, "text", "B"));
        assertThat(payload(samePositionAck)).containsEntry("transformed", true);
        assertThat(documentState(fixture.roomId().toString())).containsEntry("contentText", "AB");

        submitAck(editor, fixture.roomId().toString(), "insert-delete-seed", 3, 2, "TEXT_INSERT", Map.of("position", 2, "text", "cdef"));
        submitAck(editor, fixture.roomId().toString(), "delete-range", 4, 3, "TEXT_DELETE", Map.of("position", 1, "length", 4));
        Map<String, Object> insertInsideDelete = submitAck(editor, fixture.roomId().toString(), "insert-inside-deleted-range", 5, 3,
                "TEXT_INSERT", Map.of("position", 3, "text", "X"));
        assertThat(payload(insertInsideDelete)).containsEntry("transformed", true);

        Map<String, Object> applied = observer.nextMatching(message -> "OPERATION_APPLIED".equals(message.get("type"))
                && "insert-inside-deleted-range".equals(payload(message).get("operationId")), "transformed operation broadcast");
        assertThat(payload(applied)).containsEntry("transformed", true);
        assertThat((Map<String, Object>) payload(applied).get("operation")).containsEntry("position", 1);

        submitAck(editor, fixture.roomId().toString(), "full-delete-seed", 6, 5, "TEXT_INSERT", Map.of("position", 0, "text", "abc"));
        submitAck(editor, fixture.roomId().toString(), "delete-full-a", 7, 6, "TEXT_DELETE", Map.of("position", 0, "length", 3));
        submitAck(editor, fixture.roomId().toString(), "delete-full-b", 8, 6, "TEXT_DELETE", Map.of("position", 0, "length", 3));
        assertThat(traceDecision(fixture.roomId().toString(), "delete-full-b")).isEqualTo("NOOP_AFTER_TRANSFORM");

        submitAck(editor, fixture.roomId().toString(), "partial-delete-seed", 9, 8, "TEXT_INSERT", Map.of("position", 0, "text", "abcdef"));
        submitAck(editor, fixture.roomId().toString(), "delete-partial-a", 10, 9, "TEXT_DELETE", Map.of("position", 1, "length", 3));
        Map<String, Object> partialDelete = submitAck(editor, fixture.roomId().toString(), "delete-partial-b", 11, 9,
                "TEXT_DELETE", Map.of("position", 2, "length", 3));
        assertThat(payload(partialDelete)).containsEntry("transformed", true);

        submitAck(editor, fixture.roomId().toString(), "delete-insert-seed", 12, 11, "TEXT_INSERT", Map.of("position", 0, "text", "abcd"));
        submitAck(editor, fixture.roomId().toString(), "concurrent-insert-inside-delete", 13, 12, "TEXT_INSERT", Map.of("position", 2, "text", "X"));
        Map<String, Object> deleteOverInsert = submitAck(editor, fixture.roomId().toString(), "delete-over-concurrent-insert", 14, 12,
                "TEXT_DELETE", Map.of("position", 1, "length", 2));
        assertThat(payload(deleteOverInsert)).containsEntry("transformed", true);

        submitAck(editor, fixture.roomId().toString(), "replace-seed", 15, 14, "TEXT_INSERT", Map.of("position", 0, "text", "zz"));
        submitAck(editor, fixture.roomId().toString(), "replace-concurrent", 16, 15, "TEXT_INSERT", Map.of("position", 1, "text", "q"));
        editor.send(operationMessage("replace-stale-nack", 17, 15, "TEXT_REPLACE", Map.of("position", 0, "length", 1, "text", "r"),
                fixture.roomId().toString()));
        assertThat(payload(editor.nextOfType("OPERATION_NACK"))).containsEntry("code", "CONFLICT_REQUIRES_RESYNC");

        editor.close();
        observer.close();
    }

    @Test
    void snapshotReplayPersistsFailureAndRepeatedReplayIsIdempotent() throws Exception {
        Fixture fixture = fixture();
        TestSocket editor = joinedEditor(fixture, "pr3-snapshot-session");
        submitAck(editor, fixture.roomId().toString(), "snapshot-base", 1, 0, "TEXT_INSERT", Map.of("position", 0, "text", "hello"));
        Map<String, Object> firstSnapshot = restTemplate.postForObject(baseUrl + "/api/v1/rooms/" + fixture.roomId() + "/snapshots", Map.of(), Map.class);
        submitAck(editor, fixture.roomId().toString(), "snapshot-tail", 2, 1, "TEXT_INSERT", Map.of("position", 5, "text", "!"));
        Map<String, Object> secondSnapshot = restTemplate.postForObject(baseUrl + "/api/v1/rooms/" + fixture.roomId() + "/snapshots", Map.of(), Map.class);
        assertThat(restTemplate.getForObject(baseUrl + "/api/v1/rooms/" + fixture.roomId() + "/snapshots/latest", Map.class))
                .containsEntry("id", secondSnapshot.get("id"))
                .containsEntry("revision", 2);

        Map<String, Object> replayOne = restTemplate.postForObject(baseUrl + "/api/v1/rooms/" + fixture.roomId() + "/snapshots/replay", Map.of(), Map.class);
        Map<String, Object> replayTwo = restTemplate.postForObject(baseUrl + "/api/v1/rooms/" + fixture.roomId() + "/snapshots/replay", Map.of(), Map.class);
        assertThat(replayTwo).containsEntry("resultingChecksum", replayOne.get("resultingChecksum"));

        submitAck(editor, fixture.roomId().toString(), "snapshot-bad-tail", 3, 2, "TEXT_INSERT", Map.of("position", 6, "text", "?"));
        jdbcTemplate.update("update room_operations set operation_json = '{\"position\":999,\"text\":\"bad\"}'::jsonb where operation_id = 'snapshot-bad-tail'");
        ResponseEntity<Map> failedReplay = restTemplate.postForEntity(baseUrl + "/api/v1/rooms/" + fixture.roomId() + "/snapshots/replay", Map.of(), Map.class);
        assertThat(failedReplay.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from document_state_rebuild_runs
                where room_id = ? and status = 'FAILED'
                """, Integer.class, fixture.roomId())).isGreaterThanOrEqualTo(1);

        Fixture mismatchFixture = fixture();
        TestSocket mismatchEditor = joinedEditor(mismatchFixture, "pr3-snapshot-mismatch-session");
        submitAck(mismatchEditor, mismatchFixture.roomId().toString(), "mismatch-base", 1, 0, "TEXT_INSERT", Map.of("position", 0, "text", "ok"));
        Map<String, Object> mismatchSnapshot = restTemplate.postForObject(baseUrl + "/api/v1/rooms/" + mismatchFixture.roomId() + "/snapshots", Map.of(), Map.class);
        jdbcTemplate.update("update document_snapshots set content_text = 'corrupt' where id = ?::uuid", mismatchSnapshot.get("id").toString());
        ResponseEntity<Map> checksumMismatch = restTemplate.postForEntity(baseUrl + "/api/v1/rooms/" + mismatchFixture.roomId() + "/snapshots/replay",
                Map.of(), Map.class);
        assertThat(checksumMismatch.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        editor.close();
        mismatchEditor.close();
    }

    @Test
    void resumeTokenScopeAndBackfillIsolationAreEnforced() throws Exception {
        Fixture firstRoom = fixture();
        Fixture secondRoom = fixture();
        TestSocket firstViewer = TestSocket.connect(websocketUri(), firstRoom.viewerId(), "viewer", "scope-session", objectMapper);
        Map<String, Object> firstJoin = join(firstViewer, firstRoom.roomId().toString());
        String firstToken = payload(firstJoin).get("resumeToken").toString();

        TestSocket firstEditor = joinedEditor(firstRoom, "scope-editor");
        TestSocket secondEditor = joinedEditor(secondRoom, "scope-editor-2");
        submitAck(firstEditor, firstRoom.roomId().toString(), "first-room-op", 1, 0, "TEXT_INSERT", Map.of("position", 0, "text", "a"));
        submitAck(secondEditor, secondRoom.roomId().toString(), "second-room-op", 1, 0, "TEXT_INSERT", Map.of("position", 0, "text", "b"));

        TestSocket wrongRoom = TestSocket.connect(websocketUri(), firstRoom.viewerId(), "viewer", "scope-session", objectMapper);
        wrongRoom.send(Map.of("type", "RESUME_ROOM", "messageId", "wrong-room", "roomId", secondRoom.roomId().toString(),
                "payload", Map.of("resumeToken", firstToken)));
        assertThat(payload(wrongRoom.nextOfType("ERROR"))).containsEntry("code", "INVALID_RESUME_TOKEN");

        TestSocket wrongUser = TestSocket.connect(websocketUri(), firstRoom.ownerId(), "owner", "scope-session", objectMapper);
        wrongUser.send(Map.of("type", "RESUME_ROOM", "messageId", "wrong-user", "roomId", firstRoom.roomId().toString(),
                "payload", Map.of("resumeToken", firstToken)));
        assertThat(payload(wrongUser.nextOfType("ERROR"))).containsEntry("code", "INVALID_RESUME_TOKEN");

        jdbcTemplate.update("update room_resume_tokens set revoked_at = now() where room_id = ?", firstRoom.roomId());
        TestSocket revoked = TestSocket.connect(websocketUri(), firstRoom.viewerId(), "viewer", "scope-session", objectMapper);
        revoked.send(Map.of("type", "RESUME_ROOM", "messageId", "revoked", "roomId", firstRoom.roomId().toString(),
                "payload", Map.of("resumeToken", firstToken)));
        assertThat(payload(revoked.nextOfType("ERROR"))).containsEntry("code", "RESUME_TOKEN_EXPIRED");

        TestSocket freshViewer = TestSocket.connect(websocketUri(), firstRoom.viewerId(), "viewer-fresh", "scope-session", objectMapper);
        Map<String, Object> freshJoin = join(freshViewer, firstRoom.roomId().toString());
        String freshToken = payload(freshJoin).get("resumeToken").toString();
        for (int index = 2; index <= 103; index++) {
            submitAck(firstEditor, firstRoom.roomId().toString(), "first-room-noop-" + index, index, index - 1, "NOOP", Map.of());
        }
        TestSocket tooFar = TestSocket.connect(websocketUri(), firstRoom.viewerId(), "viewer", "scope-session", objectMapper);
        tooFar.send(Map.of("type", "RESUME_ROOM", "messageId", "too-far", "roomId", firstRoom.roomId().toString(),
                "payload", Map.of("resumeToken", freshToken, "lastSeenRoomSeq", 1)));
        tooFar.nextOfType("ROOM_RESUMED");
        Map<String, Object> resync = tooFar.nextOfType("RESYNC_REQUIRED");
        assertThat(payload(resync)).containsKeys("documentState");

        TestSocket isolated = TestSocket.connect(websocketUri(), firstRoom.viewerId(), "viewer", "scope-session", objectMapper);
        isolated.send(Map.of("type", "RESUME_ROOM", "messageId", "isolation", "roomId", firstRoom.roomId().toString(),
                "payload", Map.of("resumeToken", freshToken, "lastSeenRoomSeq", 102)));
        isolated.nextOfType("ROOM_RESUMED");
        List<Map<String, Object>> events = events(isolated.nextOfType("ROOM_BACKFILL"));
        assertThat(events).extracting(event -> event.get("operationId")).doesNotContain("second-room-op");

        firstViewer.close();
        freshViewer.close();
        firstEditor.close();
        secondEditor.close();
        wrongRoom.close();
        wrongUser.close();
        revoked.close();
        tooFar.close();
        isolated.close();
    }

    private TestSocket joinedEditor(Fixture fixture, String session) throws Exception {
        TestSocket editor = TestSocket.connect(websocketUri(), fixture.editorId(), "editor", session, objectMapper);
        join(editor, fixture.roomId().toString());
        editor.drain();
        return editor;
    }

    private TestSocket joinedViewer(Fixture fixture, String session) throws Exception {
        TestSocket viewer = TestSocket.connect(websocketUri(), fixture.viewerId(), "viewer", session, objectMapper);
        join(viewer, fixture.roomId().toString());
        viewer.drain();
        return viewer;
    }

    private Map<String, Object> join(TestSocket socket, String roomId) throws Exception {
        socket.send(Map.of("type", "JOIN_ROOM", "messageId", "join-" + roomId, "roomId", roomId, "payload", Map.of()));
        return socket.nextOfType("JOINED_ROOM");
    }

    private Map<String, Object> submitAck(TestSocket socket, String roomId, String operationId, long clientSeq, long baseRevision,
            String type, Map<String, Object> operation) throws Exception {
        socket.send(operationMessage(operationId, clientSeq, baseRevision, type, operation, roomId));
        return socket.nextOfType("OPERATION_ACK");
    }

    private Map<String, Object> operationMessage(String operationId, long clientSeq, long baseRevision, String operationType,
            Map<String, Object> operation, String roomId) {
        return Map.of("type", "SUBMIT_OPERATION", "messageId", operationId, "roomId", roomId,
                "payload", Map.of("operationId", operationId, "clientSeq", clientSeq, "baseRevision", baseRevision,
                        "operationType", operationType, "operation", operation));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> documentState(String roomId) {
        return restTemplate.getForObject(baseUrl + "/api/v1/rooms/" + roomId + "/document-state", Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("payload");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> events(Map<String, Object> envelope) {
        return (List<Map<String, Object>>) payload(envelope).get("events");
    }

    private String traceDecision(String roomId, String operationId) {
        return jdbcTemplate.queryForObject("""
                select decision from room_conflict_resolution_traces
                where room_id = ?::uuid and operation_id = ?
                order by created_at desc
                limit 1
                """, String.class, roomId, operationId);
    }
}
