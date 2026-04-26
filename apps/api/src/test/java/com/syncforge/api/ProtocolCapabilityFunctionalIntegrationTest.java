package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ProtocolCapabilityFunctionalIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    ObjectMapper objectMapper;

    @Test
    void v2ClientNegotiatesCapabilitiesSubmitsOperationAndListenerReceivesEvent() throws Exception {
        Fixture fixture = fixture();
        TestSocket editor = joinV2(fixture.editorId(), fixture.roomId().toString(), "editor",
                List.of("OPERATIONS", "AWARENESS", "PRESENCE", "RESUME", "BACKFILL", "SNAPSHOT"));
        TestSocket viewer = joinV2(fixture.viewerId(), fixture.roomId().toString(), "viewer",
                List.of("PRESENCE", "SNAPSHOT"));

        editor.send(operation(fixture.roomId().toString(), "functional-v2-op", 1, 0, 0, "hello"));

        assertThat(payload(editor.nextOfType("OPERATION_ACK"))).containsEntry("operationId", "functional-v2-op");
        Map<String, Object> applied = payload(viewer.nextOfType("OPERATION_APPLIED"));
        assertThat(applied)
                .containsEntry("operationId", "functional-v2-op")
                .containsEntry("operationType", "TEXT_INSERT");

        editor.close();
        viewer.close();
    }

    @Test
    void clientMissingCapabilityGetsCleanRejectionWithoutMutation() throws Exception {
        Fixture fixture = fixture();
        TestSocket editor = joinV2(fixture.editorId(), fixture.roomId().toString(), "limited", List.of("SNAPSHOT"));

        editor.send(operation(fixture.roomId().toString(), "blocked-functional-op", 1, 0, 0, "blocked"));

        Map<String, Object> nack = payload(editor.nextOfType("OPERATION_NACK"));
        assertThat(nack).containsEntry("code", "OPERATIONS_NOT_NEGOTIATED");
        assertThat(countRows("room_operations", fixture.roomId())).isZero();

        editor.close();
    }

    @Test
    void rejectedProtocolClientLeavesNoActiveState() throws Exception {
        Fixture fixture = fixture();
        TestSocket rejected = TestSocket.connect(websocketUri(), fixture.viewerId(), "bad-device", "bad-session", objectMapper);

        rejected.send(Map.of(
                "type", "JOIN_ROOM",
                "messageId", "bad",
                "roomId", fixture.roomId().toString(),
                "payload", Map.of("protocolVersion", -1)));

        assertThat(payload(rejected.nextOfType("PROTOCOL_REJECTED"))).containsEntry("code", "UNSUPPORTED_PROTOCOL_VERSION");
        assertThat(countRows("room_connection_sessions", fixture.roomId())).isZero();
        assertThat(countRows("websocket_protocol_sessions", fixture.roomId())).isZero();
        assertThat(countRows("websocket_connection_flow_controls", fixture.roomId())).isZero();
        assertThat(countRows("room_user_presence", fixture.roomId())).isZero();

        rejected.close();
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
        assertThat(payload(socket.nextOfType("PROTOCOL_NEGOTIATED"))).containsEntry("negotiatedProtocolVersion", 2);
        return socket;
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("payload");
    }

    private int countRows(String table, java.util.UUID roomId) {
        return jdbcTemplate.queryForObject("select count(*) from " + table + " where room_id = ?", Integer.class, roomId);
    }
}
