package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncforge.api.node.CrossNodePresenceService;
import com.syncforge.api.node.NodeIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CrossNodePresenceIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    CrossNodePresenceService crossNodePresenceService;

    @Autowired
    NodeIdentity nodeIdentity;

    @Test
    void heartbeatSubscriptionCrossNodePresenceStalenessAndRoomIsolationWork() throws Exception {
        Fixture fixture = fixture();
        Fixture other = fixture();
        assertThat(getMap("/api/v1/system/node"))
                .containsEntry("nodeId", nodeIdentity.nodeId())
                .containsEntry("status", "ACTIVE");

        TestSocket editor = TestSocket.connect(websocketUri(), fixture.editorId(), "presence-device", "presence-session", objectMapper);
        join(editor, fixture.roomId().toString());
        assertThat(jdbcTemplate.queryForObject("""
                select local_connection_count
                from node_room_subscriptions
                where node_id = ? and room_id = ?
                """, Integer.class, nodeIdentity.nodeId(), fixture.roomId())).isEqualTo(1);

        List<Map<String, Object>> nodePresence = getList("/api/v1/rooms/" + fixture.roomId() + "/presence/nodes");
        assertThat(nodePresence)
                .anySatisfy(row -> assertThat(row)
                        .containsEntry("userId", fixture.editorId().toString())
                        .containsEntry("nodeId", nodeIdentity.nodeId())
                        .containsEntry("activeConnectionCount", 1)
                        .containsEntry("status", "ONLINE"));
        assertThat(getList("/api/v1/rooms/" + other.roomId() + "/presence/nodes")).isEmpty();

        assertThat(crossNodePresenceService.markStaleNodePresence(OffsetDateTime.now().plusSeconds(31))).isGreaterThanOrEqualTo(1);
        assertThat(getList("/api/v1/rooms/" + fixture.roomId() + "/presence/nodes"))
                .anySatisfy(row -> assertThat(row)
                        .containsEntry("userId", fixture.editorId().toString())
                        .containsEntry("status", "STALE"));

        editor.send(Map.of("type", "LEAVE_ROOM", "messageId", "leave-presence", "roomId", fixture.roomId().toString(), "payload", Map.of()));
        editor.nextOfType("LEFT_ROOM");
        assertThat(getList("/api/v1/rooms/" + fixture.roomId() + "/presence/nodes"))
                .anySatisfy(row -> assertThat(row)
                        .containsEntry("userId", fixture.editorId().toString())
                        .containsEntry("status", "OFFLINE")
                        .containsEntry("activeConnectionCount", 0));
        editor.close();
    }

    private void join(TestSocket socket, String roomId) throws Exception {
        socket.send(Map.of("type", "JOIN_ROOM", "messageId", "join-" + roomId, "roomId", roomId, "payload", Map.of()));
        socket.nextOfType("JOINED_ROOM");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getList(String path) {
        return restTemplate.getForObject(baseUrl + path, List.class);
    }
}
