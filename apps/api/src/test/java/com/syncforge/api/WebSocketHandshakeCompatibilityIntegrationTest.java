package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class WebSocketHandshakeCompatibilityIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    ObjectMapper objectMapper;

    @Test
    void legacyJoinStillWorksAndReportsLegacyProtocolDefault() throws Exception {
        Fixture fixture = fixture();
        TestSocket socket = TestSocket.connect(websocketUri(), fixture.ownerId(), "legacy-device", "legacy-session", objectMapper);

        socket.send(Map.of("type", "JOIN_ROOM", "messageId", "legacy-join", "roomId", fixture.roomId().toString(), "payload", Map.of()));

        Map<String, Object> joined = socket.nextOfType("JOINED_ROOM");
        assertThat(joined).containsEntry("type", "JOINED_ROOM");
        Map<String, Object> negotiated = payload(socket.nextOfType("PROTOCOL_NEGOTIATED"));
        assertThat(negotiated)
                .containsEntry("negotiatedProtocolVersion", 1)
                .containsEntry("serverPreferredProtocolVersion", 2)
                .containsEntry("legacyDefaultApplied", true);
        assertThat((List<String>) negotiated.get("enabledCapabilities"))
                .containsExactly("OPERATIONS", "AWARENESS", "PRESENCE", "RESUME", "BACKFILL", "SNAPSHOT");

        socket.close();
    }

    @Test
    void v2JoinNegotiatesRequestedCapabilities() throws Exception {
        Fixture fixture = fixture();
        TestSocket socket = TestSocket.connect(websocketUri(), fixture.editorId(), "v2-device", "v2-session", objectMapper);

        socket.send(Map.of(
                "type", "JOIN_ROOM",
                "messageId", "v2-join",
                "roomId", fixture.roomId().toString(),
                "payload", Map.of(
                        "protocolVersion", 2,
                        "clientId", "client-v2",
                        "capabilities", List.of("OPERATIONS", "AWARENESS", "SNAPSHOT", "OFFLINE_EDITS", "UNKNOWN_THING"))));

        socket.nextOfType("JOINED_ROOM");
        Map<String, Object> negotiated = payload(socket.nextOfType("PROTOCOL_NEGOTIATED"));
        assertThat(negotiated)
                .containsEntry("clientId", "client-v2")
                .containsEntry("requestedProtocolVersion", 2)
                .containsEntry("negotiatedProtocolVersion", 2)
                .containsEntry("legacyDefaultApplied", false);
        assertThat((List<String>) negotiated.get("enabledCapabilities"))
                .containsExactly("OPERATIONS", "AWARENESS", "SNAPSHOT");
        assertThat((List<Map<String, Object>>) negotiated.get("disabledCapabilities"))
                .extracting(item -> item.get("capability"))
                .contains("OFFLINE_EDITS", "RESUME", "BACKFILL");
        assertThat((List<Map<String, Object>>) negotiated.get("rejectedCapabilities"))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.get("capability")).isEqualTo("UNKNOWN_THING");
                    assertThat(item.get("code")).isEqualTo("UNKNOWN_CAPABILITY");
                });

        socket.close();
    }

    @Test
    void rejectedProtocolJoinCreatesNoPresenceSessionFlowControlSubscriptionOrBroadcastDelivery() throws Exception {
        Fixture fixture = fixture();
        TestSocket rejected = TestSocket.connect(websocketUri(), fixture.viewerId(), "bad-device", "bad-session", objectMapper);

        rejected.send(Map.of(
                "type", "JOIN_ROOM",
                "messageId", "bad-protocol",
                "roomId", fixture.roomId().toString(),
                "payload", Map.of("protocolVersion", 99)));

        Map<String, Object> rejection = payload(rejected.nextOfType("PROTOCOL_REJECTED"));
        assertThat(rejection)
                .containsEntry("code", "UNSUPPORTED_PROTOCOL_VERSION")
                .containsEntry("serverPreferredProtocolVersion", 2)
                .containsEntry("minimumSupportedProtocolVersion", 1)
                .containsEntry("maximumSupportedProtocolVersion", 2);
        assertThat(countRows("room_user_presence", fixture.roomId())).isZero();
        assertThat(countRows("room_presence_connections", fixture.roomId())).isZero();
        assertThat(countRows("room_connection_sessions", fixture.roomId())).isZero();
        assertThat(countRows("websocket_connection_flow_controls", fixture.roomId())).isZero();
        assertThat(countRows("node_room_subscriptions", fixture.roomId())).isZero();

        TestSocket owner = TestSocket.connect(websocketUri(), fixture.ownerId(), "owner-device", "owner-session", objectMapper);
        owner.send(Map.of("type", "JOIN_ROOM", "messageId", "owner-join", "roomId", fixture.roomId().toString(), "payload", Map.of()));
        owner.nextOfType("JOINED_ROOM");
        owner.send(Map.of(
                "type", "SUBMIT_OPERATION",
                "messageId", "broadcast-check",
                "roomId", fixture.roomId().toString(),
                "payload", Map.of(
                        "operationId", "protocol-rejected-broadcast-check",
                        "clientSeq", 1,
                        "baseRevision", 0,
                        "operationType", "TEXT_INSERT",
                        "operation", Map.of("position", 0, "text", "safe"))));
        owner.nextOfType("OPERATION_ACK");

        assertThat(rejected.hasMessageOfTypeWithin("OPERATION_APPLIED", 300)).isFalse();

        owner.close();
        rejected.close();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("payload");
    }

    private int countRows(String table, java.util.UUID roomId) {
        return jdbcTemplate.queryForObject("select count(*) from " + table + " where room_id = ?", Integer.class, roomId);
    }
}
