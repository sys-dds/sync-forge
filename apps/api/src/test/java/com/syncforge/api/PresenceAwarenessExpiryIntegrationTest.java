package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PresenceAwarenessExpiryIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    ObjectMapper objectMapper;

    @Test
    void multiDevicePresenceOnlyGoesOfflineAfterLastActiveConnectionLeaves() throws Exception {
        Fixture fixture = fixture();
        TestSocket first = TestSocket.connect(websocketUri(), fixture.ownerId(), "device-one", "session-one", objectMapper);
        TestSocket second = TestSocket.connect(websocketUri(), fixture.ownerId(), "device-two", "session-two", objectMapper);
        join(first, fixture.roomId().toString());
        join(second, fixture.roomId().toString());

        Map<String, Object> online = onlyPresence(fixture.roomId().toString());
        assertThat(online).containsEntry("status", "ONLINE").containsEntry("activeConnectionCount", 2);

        first.send(Map.of("type", "LEAVE_ROOM", "messageId", "leave-one", "roomId", fixture.roomId().toString(), "payload", Map.of()));
        first.nextOfType("LEFT_ROOM");
        Map<String, Object> stillOnline = onlyPresence(fixture.roomId().toString());
        assertThat(stillOnline).containsEntry("status", "ONLINE").containsEntry("activeConnectionCount", 1);

        second.send(Map.of("type", "LEAVE_ROOM", "messageId", "leave-two", "roomId", fixture.roomId().toString(), "payload", Map.of()));
        second.nextOfType("LEFT_ROOM");
        Map<String, Object> offline = onlyPresence(fixture.roomId().toString());
        assertThat(offline).containsEntry("status", "OFFLINE").containsEntry("activeConnectionCount", 0);
        first.close();
        second.close();
    }

    @Test
    void expiredPresenceAndAwarenessNoLongerAppearActive() throws Exception {
        Fixture fixture = fixture();
        TestSocket viewer = TestSocket.connect(websocketUri(), fixture.viewerId(), "viewer-device", "viewer-session", objectMapper);
        join(viewer, fixture.roomId().toString());
        viewer.send(Map.of(
                "type", "CURSOR_UPDATE",
                "messageId", "cursor",
                "roomId", fixture.roomId().toString(),
                "payload", Map.of("cursorPosition", 4, "metadata", Map.of())));
        viewer.nextOfType("AWARENESS_UPDATED");

        assertThat(getList("/api/v1/rooms/" + fixture.roomId() + "/awareness")).hasSize(1);
        OffsetDateTime future = OffsetDateTime.now().plusMinutes(5);
        restTemplate.postForObject(baseUrl + "/api/v1/rooms/" + fixture.roomId() + "/awareness/expire",
                Map.of("now", future.toString()), Map.class);
        restTemplate.postForObject(baseUrl + "/api/v1/rooms/" + fixture.roomId() + "/presence/expire",
                Map.of("now", future.toString()), Map.class);

        assertThat(getList("/api/v1/rooms/" + fixture.roomId() + "/awareness")).isEmpty();
        Map<String, Object> expiredPresence = onlyPresence(fixture.roomId().toString());
        assertThat(expiredPresence).containsEntry("status", "OFFLINE").containsEntry("activeConnectionCount", 0);
        viewer.close();
    }

    private void join(TestSocket socket, String roomId) throws Exception {
        socket.send(Map.of("type", "JOIN_ROOM", "messageId", "join-" + roomId, "roomId", roomId, "payload", Map.of()));
        socket.nextOfType("JOINED_ROOM");
        socket.drain();
    }

    private Map<String, Object> onlyPresence(String roomId) {
        List<Map<String, Object>> rows = getList("/api/v1/rooms/" + roomId + "/presence");
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getList(String path) {
        return restTemplate.getForObject(baseUrl + path, List.class);
    }
}
