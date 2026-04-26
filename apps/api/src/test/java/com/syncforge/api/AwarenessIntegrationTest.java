package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AwarenessIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    ObjectMapper objectMapper;

    @Test
    void joinedUsersIncludingViewersCanUpdateCursorAndSelection() throws Exception {
        Fixture fixture = fixture();
        TestSocket owner = TestSocket.connect(websocketUri(), fixture.ownerId(), "owner-device", "owner-session", objectMapper);
        TestSocket viewer = TestSocket.connect(websocketUri(), fixture.viewerId(), "viewer-device", "viewer-session", objectMapper);
        join(owner, fixture.roomId().toString());
        join(viewer, fixture.roomId().toString());

        owner.send(Map.of("type", "CURSOR_UPDATE", "messageId", "cursor", "roomId", fixture.roomId().toString(),
                "payload", Map.of("cursorPosition", 12, "metadata", Map.of("color", "blue"))));
        owner.nextOfType("AWARENESS_UPDATED");
        viewer.drain();
        viewer.send(Map.of("type", "SELECTION_UPDATE", "messageId", "selection", "roomId", fixture.roomId().toString(),
                "payload", Map.of("anchorPosition", 10, "focusPosition", 18, "metadata", Map.of())));
        viewer.nextMatching(message -> "AWARENESS_UPDATED".equals(message.get("type"))
                && "selection".equals(message.get("messageId")), "selection awareness update");

        List<Map<String, Object>> awareness = getList("/api/v1/rooms/" + fixture.roomId() + "/awareness");
        assertThat(awareness).anySatisfy(row -> {
            assertThat(row).containsEntry("userId", fixture.ownerId().toString());
            assertThat(row).containsEntry("awarenessType", "CURSOR");
            assertThat(row).containsEntry("cursorPosition", 12);
        });
        assertThat(awareness).anySatisfy(row -> {
            assertThat(row).containsEntry("userId", fixture.viewerId().toString());
            assertThat(row).containsEntry("awarenessType", "SELECTION");
            assertThat(row).containsEntry("anchorPosition", 10);
            assertThat(row).containsEntry("focusPosition", 18);
        });
        owner.close();
        viewer.close();
    }

    @Test
    void nonJoinedSocketIsRejectedAndAwarenessCanExpire() throws Exception {
        Fixture fixture = fixture();
        TestSocket client = TestSocket.connect(websocketUri(), fixture.ownerId(), "device", "session", objectMapper);
        client.send(Map.of("type", "CURSOR_UPDATE", "messageId", "cursor-first", "roomId", fixture.roomId().toString(),
                "payload", Map.of("cursorPosition", 1)));
        assertThat(payload(client.nextOfType("ERROR"))).containsEntry("code", "CONNECTION_NOT_JOINED");

        join(client, fixture.roomId().toString());
        client.send(Map.of("type", "CURSOR_UPDATE", "messageId", "cursor", "roomId", fixture.roomId().toString(),
                "payload", Map.of("cursorPosition", 4)));
        client.nextOfType("AWARENESS_UPDATED");
        restTemplate.postForEntity(baseUrl + "/api/v1/rooms/" + fixture.roomId() + "/awareness/expire",
                Map.of("now", OffsetDateTime.now().plusSeconds(60).toString()), Map.class);
        assertThat(getList("/api/v1/rooms/" + fixture.roomId() + "/awareness")).isEmpty();
        client.close();
    }

    @Test
    void awarenessBroadcastsAreRoomScoped() throws Exception {
        Fixture fixture = fixture();
        Fixture other = fixture();
        TestSocket sender = TestSocket.connect(websocketUri(), fixture.ownerId(), "sender-device", "sender-session", objectMapper);
        TestSocket sameRoom = TestSocket.connect(websocketUri(), fixture.editorId(), "same-device", "same-session", objectMapper);
        TestSocket otherRoom = TestSocket.connect(websocketUri(), other.ownerId(), "other-device", "other-session", objectMapper);
        join(sender, fixture.roomId().toString());
        join(sameRoom, fixture.roomId().toString());
        join(otherRoom, other.roomId().toString());
        sender.drain();
        sameRoom.drain();
        otherRoom.drain();

        sender.send(Map.of("type", "CURSOR_UPDATE", "messageId", "cursor", "roomId", fixture.roomId().toString(),
                "payload", Map.of("cursorPosition", 7, "metadata", Map.of())));
        assertThat(sameRoom.nextOfType("AWARENESS_UPDATED")).containsEntry("roomId", fixture.roomId().toString());
        assertThat(otherRoom.hasMessageOfTypeWithin("AWARENESS_UPDATED", 300)).isFalse();
        sender.close();
        sameRoom.close();
        otherRoom.close();
    }

    private void join(TestSocket socket, String roomId) throws Exception {
        socket.send(Map.of("type", "JOIN_ROOM", "messageId", "join", "roomId", roomId, "payload", Map.of()));
        socket.nextOfType("JOINED_ROOM");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getList(String path) {
        return restTemplate.getForObject(baseUrl + path, List.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("payload");
    }
}
