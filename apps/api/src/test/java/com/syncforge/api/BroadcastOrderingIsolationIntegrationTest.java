package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class BroadcastOrderingIsolationIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    ObjectMapper objectMapper;

    @Test
    void operationBroadcastsStayOrderedAndRoomScoped() throws Exception {
        Fixture firstRoom = fixture();
        Fixture secondRoom = fixture();
        TestSocket sender = TestSocket.connect(websocketUri(), firstRoom.editorId(), "sender", "sender-session", objectMapper);
        TestSocket listener = TestSocket.connect(websocketUri(), firstRoom.ownerId(), "listener", "listener-session", objectMapper);
        TestSocket otherRoom = TestSocket.connect(websocketUri(), secondRoom.ownerId(), "other", "other-session", objectMapper);
        join(sender, firstRoom.roomId().toString());
        join(listener, firstRoom.roomId().toString());
        join(otherRoom, secondRoom.roomId().toString());
        sender.drain();
        listener.drain();
        otherRoom.drain();

        sender.send(operationMessage("ordered-broadcast-1", 1, 0, "NOOP", Map.of(), firstRoom.roomId().toString()));
        assertThat(payload(sender.nextOfType("OPERATION_ACK"))).containsEntry("roomSeq", 1);
        sender.send(operationMessage("ordered-broadcast-2", 2, 1, "NOOP", Map.of(), firstRoom.roomId().toString()));
        assertThat(payload(sender.nextOfType("OPERATION_ACK"))).containsEntry("roomSeq", 2);

        assertThat(payload(listener.nextOfType("OPERATION_APPLIED"))).containsEntry("roomSeq", 1);
        assertThat(payload(listener.nextOfType("OPERATION_APPLIED"))).containsEntry("roomSeq", 2);
        assertThat(otherRoom.hasMessageOfTypeWithin("OPERATION_APPLIED", 300)).isFalse();
        sender.close();
        listener.close();
        otherRoom.close();
    }

    @Test
    void leftAndDisconnectedClientsDoNotReceiveOperationBroadcasts() throws Exception {
        Fixture fixture = fixture();
        TestSocket sender = TestSocket.connect(websocketUri(), fixture.editorId(), "sender", "sender-session", objectMapper);
        TestSocket left = TestSocket.connect(websocketUri(), fixture.ownerId(), "left", "left-session", objectMapper);
        TestSocket closed = TestSocket.connect(websocketUri(), fixture.viewerId(), "closed", "closed-session", objectMapper);
        join(sender, fixture.roomId().toString());
        join(left, fixture.roomId().toString());
        join(closed, fixture.roomId().toString());
        sender.drain();
        left.drain();
        closed.drain();

        left.send(Map.of("type", "LEAVE_ROOM", "messageId", "leave", "roomId", fixture.roomId().toString(), "payload", Map.of()));
        left.nextOfType("LEFT_ROOM");
        left.drain();
        closed.close();

        sender.send(operationMessage("after-left-close", 1, 0, "NOOP", Map.of(), fixture.roomId().toString()));
        assertThat(payload(sender.nextOfType("OPERATION_ACK"))).containsEntry("roomSeq", 1);
        assertThat(left.hasMessageOfTypeWithin("OPERATION_APPLIED", 300)).isFalse();
        sender.close();
        left.close();
    }

    private void join(TestSocket socket, String roomId) throws Exception {
        socket.send(Map.of("type", "JOIN_ROOM", "messageId", "join-" + roomId, "roomId", roomId, "payload", Map.of()));
        socket.nextOfType("JOINED_ROOM");
    }

    private Map<String, Object> operationMessage(
            String operationId,
            long clientSeq,
            long baseRevision,
            String operationType,
            Map<String, Object> operation,
            String roomId) {
        return Map.of(
                "type", "SUBMIT_OPERATION",
                "messageId", operationId + "-message",
                "roomId", roomId,
                "payload", Map.of(
                        "operationId", operationId,
                        "clientSeq", clientSeq,
                        "baseRevision", baseRevision,
                        "operationType", operationType,
                        "operation", operation));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("payload");
    }
}
