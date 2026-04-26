package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class Pr6CarryForwardHardeningIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    ObjectMapper objectMapper;

    @Test
    void protocolCapabilityPermissionBaselineStillAcceptsAuthorizedV2Operation() throws Exception {
        Fixture fixture = fixture();
        TestSocket editor = TestSocket.connect(websocketUri(), fixture.editorId(), "pr6-device", "pr6-session",
                objectMapper);

        editor.send(Map.of(
                "type", "JOIN_ROOM",
                "messageId", "join",
                "roomId", fixture.roomId().toString(),
                "payload", Map.of(
                        "protocolVersion", 2,
                        "clientId", "pr6-client",
                        "capabilities", List.of("OPERATIONS", "AWARENESS", "PRESENCE", "RESUME", "BACKFILL", "SNAPSHOT"))));
        assertThat(editor.nextOfType("JOINED_ROOM").get("type")).isEqualTo("JOINED_ROOM");
        assertThat(editor.nextOfType("PROTOCOL_NEGOTIATED").get("type")).isEqualTo("PROTOCOL_NEGOTIATED");

        editor.send(Map.of(
                "type", "SUBMIT_OPERATION",
                "messageId", "op",
                "roomId", fixture.roomId().toString(),
                "payload", Map.of(
                        "operationId", "pr6-baseline-op",
                        "clientSeq", 1,
                        "baseRevision", 0,
                        "operationType", "TEXT_INSERT",
                        "operation", Map.of("position", 0, "text", "p"))));

        assertThat(payload(editor.nextOfType("OPERATION_ACK"))).containsEntry("operationId", "pr6-baseline-op");
        assertThat(jdbcTemplate.queryForObject("select count(*) from room_event_outbox", Integer.class)).isEqualTo(1);
        editor.close();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("payload");
    }
}
