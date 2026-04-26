package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PresenceIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    ObjectMapper objectMapper;

    @Test
    void joinViewerAndNonMemberPresenceRulesHold() throws Exception {
        Fixture fixture = fixture();
        TestSocket viewer = TestSocket.connect(websocketUri(), fixture.viewerId(), "viewer-device", "viewer-session", objectMapper);
        viewer.send(Map.of("type", "JOIN_ROOM", "messageId", "viewer-join", "roomId", fixture.roomId().toString(), "payload", Map.of()));
        viewer.nextOfType("JOINED_ROOM");

        List<Map<String, Object>> presence = getList("/api/v1/rooms/" + fixture.roomId() + "/presence");
        assertThat(presence).anySatisfy(row -> {
            assertThat(row).containsEntry("userId", fixture.viewerId().toString());
            assertThat(row).containsEntry("status", "ONLINE");
        });

        TestSocket outsider = TestSocket.connect(websocketUri(), fixture.outsiderId(), "out-device", "out-session", objectMapper);
        outsider.send(Map.of("type", "JOIN_ROOM", "messageId", "outsider-join", "roomId", fixture.roomId().toString(), "payload", Map.of()));
        assertThat(payload(outsider.nextOfType("ERROR"))).containsEntry("code", "ROOM_ACCESS_DENIED");

        viewer.close();
        outsider.close();
    }

    @Test
    void leaveAndSocketCloseMarkUserOfflineWhenNoActiveConnectionRemains() throws Exception {
        Fixture fixture = fixture();
        TestSocket client = TestSocket.connect(websocketUri(), fixture.ownerId(), "device-a", "session-a", objectMapper);
        client.send(Map.of("type", "JOIN_ROOM", "messageId", "join", "roomId", fixture.roomId().toString(), "payload", Map.of()));
        client.nextOfType("JOINED_ROOM");
        client.send(Map.of("type", "LEAVE_ROOM", "messageId", "leave", "roomId", fixture.roomId().toString(), "payload", Map.of()));
        client.nextOfType("LEFT_ROOM");
        assertOffline(fixture.ownerId().toString(), fixture.roomId().toString());
        client.close();

        TestSocket closeClient = TestSocket.connect(websocketUri(), fixture.editorId(), "device-b", "session-b", objectMapper);
        closeClient.send(Map.of("type", "JOIN_ROOM", "messageId", "join-close", "roomId", fixture.roomId().toString(), "payload", Map.of()));
        closeClient.nextOfType("JOINED_ROOM");
        closeClient.close();
        Thread.sleep(250);
        assertOffline(fixture.editorId().toString(), fixture.roomId().toString());
    }

    @Test
    void heartbeatTtlExpiryAndMultiDeviceMergeWork() throws Exception {
        Fixture fixture = fixture();
        TestSocket first = TestSocket.connect(websocketUri(), fixture.ownerId(), "device-a", "session-a", objectMapper);
        TestSocket second = TestSocket.connect(websocketUri(), fixture.ownerId(), "device-b", "session-b", objectMapper);
        first.send(Map.of("type", "JOIN_ROOM", "messageId", "join-a", "roomId", fixture.roomId().toString(), "payload", Map.of()));
        second.send(Map.of("type", "JOIN_ROOM", "messageId", "join-b", "roomId", fixture.roomId().toString(), "payload", Map.of()));
        first.nextOfType("JOINED_ROOM");
        second.nextOfType("JOINED_ROOM");

        first.send(Map.of("type", "HEARTBEAT", "messageId", "heartbeat", "roomId", fixture.roomId().toString(), "payload", Map.of("status", "ACTIVE")));
        first.nextOfType("PONG");
        List<Map<String, Object>> merged = getList("/api/v1/rooms/" + fixture.roomId() + "/presence");
        assertThat(merged).anySatisfy(row -> {
            assertThat(row).containsEntry("userId", fixture.ownerId().toString());
            assertThat(row).containsEntry("activeConnectionCount", 2);
            assertThat(row.get("activeDeviceIds").toString()).contains("device-a", "device-b");
        });

        first.send(Map.of("type", "LEAVE_ROOM", "messageId", "leave-a", "roomId", fixture.roomId().toString(), "payload", Map.of()));
        first.nextOfType("LEFT_ROOM");
        List<Map<String, Object>> afterOneLeaves = getList("/api/v1/rooms/" + fixture.roomId() + "/presence");
        assertThat(afterOneLeaves).anySatisfy(row -> {
            assertThat(row).containsEntry("userId", fixture.ownerId().toString());
            assertThat(row).containsEntry("status", "ONLINE");
            assertThat(row).containsEntry("activeConnectionCount", 1);
        });

        restTemplate.postForEntity(baseUrl + "/api/v1/rooms/" + fixture.roomId() + "/presence/expire",
                Map.of("now", OffsetDateTime.now().plusSeconds(60).toString()), Map.class);
        List<Map<String, Object>> expiredConnections = getList("/api/v1/rooms/" + fixture.roomId() + "/presence/connections");
        assertThat(expiredConnections).anySatisfy(row -> assertThat(row).containsEntry("status", "EXPIRED"));
        assertOffline(fixture.ownerId().toString(), fixture.roomId().toString());
        first.close();
        second.close();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getList(String path) {
        return restTemplate.getForObject(baseUrl + path, List.class);
    }

    private void assertOffline(String userId, String roomId) {
        List<Map<String, Object>> presence = getList("/api/v1/rooms/" + roomId + "/presence");
        assertThat(presence).anySatisfy(row -> {
            assertThat(row).containsEntry("userId", userId);
            assertThat(row).containsEntry("status", "OFFLINE");
        });
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("payload");
    }
}
