package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncforge.api.operation.application.OperationService;
import com.syncforge.api.operation.model.OperationSubmitResult;
import com.syncforge.api.operation.model.SubmitOperationCommand;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ConflictTraceRegressionIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    OperationService operationService;

    @Test
    void directApplyTracePolicyAndTransformTraceMatrixArePersisted() {
        Fixture direct = fixture();
        assertThat(submit(direct, "direct-apply", 1, 0, "TEXT_INSERT", Map.of("position", 0, "text", "d")).accepted())
                .isTrue();
        assertThat(traceCount(direct.roomId(), "direct-apply")).as("direct apply currently does not require a trace").isZero();

        Fixture insertTie = fixture();
        submit(insertTie, "a-insert-tie", 1, 0, "TEXT_INSERT", Map.of("position", 0, "text", "A"));
        OperationSubmitResult tied = submit(insertTie, "b-insert-tie", 2, 0, "TEXT_INSERT", Map.of("position", 0, "text", "B"));
        assertThat(tied.accepted()).isTrue();
        assertThat(tied.transformed()).isTrue();
        assertThat(assertTrace(insertTie.roomId(), "b-insert-tie", "TRANSFORMED_APPLY"))
                .containsEntry("base_revision", 0L)
                .containsEntry("current_revision", 1L);
        assertThat(traceJson(insertTie.roomId(), "b-insert-tie", "transformed_operation_json"))
                .contains("\"position\": 1")
                .contains("\"text\": \"B\"");

        Fixture fullOverlap = fixture();
        submit(fullOverlap, "seed-full", 1, 0, "TEXT_INSERT", Map.of("position", 0, "text", "abcd"));
        submit(fullOverlap, "delete-full-a", 2, 1, "TEXT_DELETE", Map.of("position", 1, "length", 2));
        OperationSubmitResult fullNoop = submit(fullOverlap, "delete-full-b", 3, 1, "TEXT_DELETE", Map.of("position", 1, "length", 2));
        assertThat(fullNoop.accepted()).isTrue();
        assertThat(fullNoop.operationType()).isEqualTo("NOOP");
        assertThat(assertTrace(fullOverlap.roomId(), "delete-full-b", "NOOP_AFTER_TRANSFORM"))
                .containsEntry("reason", "stale operation transformed against concurrent room operations");

        Fixture partialOverlap = fixture();
        submit(partialOverlap, "seed-partial", 1, 0, "TEXT_INSERT", Map.of("position", 0, "text", "abcdef"));
        submit(partialOverlap, "delete-partial-a", 2, 1, "TEXT_DELETE", Map.of("position", 1, "length", 3));
        OperationSubmitResult partial = submit(partialOverlap, "delete-partial-b", 3, 1, "TEXT_DELETE",
                Map.of("position", 2, "length", 3));
        assertThat(partial.accepted()).isTrue();
        assertThat(partial.operationType()).isEqualTo("TEXT_DELETE");
        assertTrace(partialOverlap.roomId(), "delete-partial-b", "TRANSFORMED_APPLY");

        Fixture insertInsideDeletedRange = fixture();
        submit(insertInsideDeletedRange, "seed-inside-delete", 1, 0, "TEXT_INSERT", Map.of("position", 0, "text", "abcdef"));
        submit(insertInsideDeletedRange, "delete-range", 2, 1, "TEXT_DELETE", Map.of("position", 1, "length", 3));
        OperationSubmitResult inside = submit(insertInsideDeletedRange, "insert-inside-delete", 3, 1, "TEXT_INSERT",
                Map.of("position", 2, "text", "X"));
        assertThat(inside.accepted()).isTrue();
        assertThat(inside.operation()).containsEntry("position", 1);
        assertTrace(insertInsideDeletedRange.roomId(), "insert-inside-delete", "TRANSFORMED_APPLY");

        Fixture deleteOverInsert = fixture();
        submit(deleteOverInsert, "seed-delete-insert", 1, 0, "TEXT_INSERT", Map.of("position", 0, "text", "ab"));
        submit(deleteOverInsert, "concurrent-insert", 2, 1, "TEXT_INSERT", Map.of("position", 1, "text", "X"));
        OperationSubmitResult delete = submit(deleteOverInsert, "delete-over-insert", 3, 1, "TEXT_DELETE",
                Map.of("position", 0, "length", 2));
        assertThat(delete.accepted()).isTrue();
        assertThat(delete.operation()).containsEntry("length", 3);
        assertTrace(deleteOverInsert.roomId(), "delete-over-insert", "TRANSFORMED_APPLY");

        Fixture unsafeReplace = fixture();
        submit(unsafeReplace, "seed-replace", 1, 0, "TEXT_INSERT", Map.of("position", 0, "text", "abc"));
        submit(unsafeReplace, "insert-before-replace", 2, 1, "TEXT_INSERT", Map.of("position", 1, "text", "x"));
        OperationSubmitResult rejected = submit(unsafeReplace, "unsafe-replace-trace", 3, 1, "TEXT_REPLACE",
                Map.of("position", 0, "length", 2, "text", "yy"));
        assertThat(rejected.accepted()).isFalse();
        assertThat(rejected.code()).isEqualTo("CONFLICT_REQUIRES_RESYNC");
        assertThat(assertTrace(unsafeReplace.roomId(), "unsafe-replace-trace", "REJECTED_REQUIRES_RESYNC"))
                .containsEntry("reason", "replace conflict requires resync");
    }

    @Test
    void transformedPayloadIsBroadcastAndTraceContainsRequiredFields() throws Exception {
        Fixture fixture = fixture();
        TestSocket editor = joinedEditor(fixture, "trace-editor-session");
        TestSocket listener = joinedEditor(fixture, "trace-listener-session");
        editor.drain();
        listener.drain();

        submitAck(editor, fixture.roomId().toString(), "broadcast-a", 1, 0, "TEXT_INSERT",
                Map.of("position", 0, "text", "A"));
        listener.nextOfType("OPERATION_APPLIED");
        Map<String, Object> ack = submitAck(editor, fixture.roomId().toString(), "broadcast-b", 2, 0, "TEXT_INSERT",
                Map.of("position", 0, "text", "B"));
        assertThat(payload(ack)).containsEntry("transformed", true);
        Map<String, Object> appliedPayload = payload(listener.nextOfType("OPERATION_APPLIED"));
        assertThat(appliedPayload).containsEntry("operationId", "broadcast-b");
        assertThat(castMap(appliedPayload.get("operation"))).containsEntry("position", 1).containsEntry("text", "B");

        Map<String, Object> trace = assertTrace(fixture.roomId(), "broadcast-b", "TRANSFORMED_APPLY");
        assertThat(trace.get("operation_id")).isEqualTo("broadcast-b");
        assertThat(trace.get("base_revision")).isEqualTo(0L);
        assertThat(trace.get("current_revision")).isEqualTo(1L);
        assertThat(trace.get("decision")).isEqualTo("TRANSFORMED_APPLY");
        assertThat(trace.get("reason")).isEqualTo("stale operation transformed against concurrent room operations");
        assertThat(trace.get("incoming_operation_json").toString()).contains("\"position\": 0");
        assertThat(trace.get("transformed_operation_json").toString()).contains("\"position\": 1");

        editor.close();
        listener.close();
    }

    private OperationSubmitResult submit(
            Fixture fixture,
            String operationId,
            long clientSeq,
            long baseRevision,
            String operationType,
            Map<String, Object> operation) {
        return operationService.submit(new SubmitOperationCommand(
                fixture.roomId(),
                fixture.editorId(),
                "conflict-trace-connection",
                "conflict-trace-session",
                operationId,
                clientSeq,
                baseRevision,
                operationType,
                operation));
    }

    private TestSocket joinedEditor(Fixture fixture, String session) throws Exception {
        TestSocket socket = TestSocket.connect(websocketUri(), fixture.editorId(), "trace-device-" + session, session, objectMapper);
        socket.send(Map.of("type", "JOIN_ROOM", "messageId", "join-" + session, "roomId", fixture.roomId().toString(), "payload", Map.of()));
        socket.nextOfType("JOINED_ROOM");
        return socket;
    }

    private Map<String, Object> submitAck(TestSocket socket, String roomId, String operationId, long clientSeq, long baseRevision,
            String operationType, Map<String, Object> operation) throws Exception {
        socket.send(Map.of("type", "SUBMIT_OPERATION", "messageId", operationId, "roomId", roomId,
                "payload", Map.of("operationId", operationId, "clientSeq", clientSeq, "baseRevision", baseRevision,
                        "operationType", operationType, "operation", operation)));
        return socket.nextOfType("OPERATION_ACK");
    }

    private Map<String, Object> assertTrace(UUID roomId, String operationId, String decision) {
        List<Map<String, Object>> traces = jdbcTemplate.queryForList("""
                select operation_id, base_revision, current_revision, decision, reason,
                       incoming_operation_json::text as incoming_operation_json,
                       concurrent_operations_json::text as concurrent_operations_json,
                       transformed_operation_json::text as transformed_operation_json
                from room_conflict_resolution_traces
                where room_id = ? and operation_id = ?
                """, roomId, operationId);
        assertThat(traces).hasSize(1);
        assertThat(traces.getFirst()).containsEntry("decision", decision);
        assertThat(traces.getFirst().get("incoming_operation_json").toString()).isNotBlank();
        assertThat(traces.getFirst().get("concurrent_operations_json").toString()).isNotBlank();
        return traces.getFirst();
    }

    private int traceCount(UUID roomId, String operationId) {
        return jdbcTemplate.queryForObject("""
                select count(*)
                from room_conflict_resolution_traces
                where room_id = ? and operation_id = ?
                """, Integer.class, roomId, operationId);
    }

    private String traceJson(UUID roomId, String operationId, String column) {
        return jdbcTemplate.queryForObject("""
                select %s::text
                from room_conflict_resolution_traces
                where room_id = ? and operation_id = ?
                """.formatted(column), String.class, roomId, operationId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("payload");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }
}
