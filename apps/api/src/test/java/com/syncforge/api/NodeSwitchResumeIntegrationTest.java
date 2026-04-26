package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncforge.api.node.NodeIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {
        "syncforge.resume.max-backfill-events=2",
        "syncforge.rate-limit.operations-per-connection-per-second=50",
        "syncforge.rate-limit.operations-per-user-per-room-per-minute=100"
})
class NodeSwitchResumeIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    NodeIdentity nodeIdentity;

    @Test
    void simulatedNodeSwitchBackfillsMissedEventsWithoutDuplicatesAndStillResyncsWhenTooFarBehind() throws Exception {
        String simulatedNodeA = nodeIdentity.nodeId();
        String simulatedNodeB = "simulated-node-b";
        jdbcTemplate.update("""
                insert into syncforge_node_heartbeats (node_id, status)
                values (?, 'ACTIVE')
                on conflict (node_id) do update set status = 'ACTIVE', last_seen_at = now()
                """, simulatedNodeA);
        jdbcTemplate.update("""
                insert into syncforge_node_heartbeats (node_id, status)
                values (?, 'ACTIVE')
                on conflict (node_id) do update set status = 'ACTIVE', last_seen_at = now()
                """, simulatedNodeB);

        Fixture fixture = fixture();
        TestSocket viewerOnNodeA = TestSocket.connect(websocketUri(), fixture.viewerId(), "viewer-a", "node-switch-viewer", objectMapper);
        Map<String, Object> viewerJoin = join(viewerOnNodeA, fixture.roomId().toString());
        String resumeToken = payload(viewerJoin).get("resumeToken").toString();
        viewerOnNodeA.close();

        TestSocket editor = TestSocket.connect(websocketUri(), fixture.editorId(), "editor", "node-switch-editor", objectMapper);
        join(editor, fixture.roomId().toString());
        submitAck(editor, fixture.roomId().toString(), "switch-1", 1, 0, "TEXT_INSERT", Map.of("position", 0, "text", "a"));
        submitAck(editor, fixture.roomId().toString(), "switch-2", 2, 1, "TEXT_INSERT", Map.of("position", 1, "text", "b"));

        TestSocket viewerOnNodeB = TestSocket.connect(websocketUri(), fixture.viewerId(), "viewer-b", "node-switch-viewer", objectMapper);
        viewerOnNodeB.send(Map.of("type", "RESUME_ROOM", "messageId", "resume-on-node-b", "roomId", fixture.roomId().toString(),
                "payload", Map.of("resumeToken", resumeToken, "lastSeenRoomSeq", 0)));
        viewerOnNodeB.nextOfType("ROOM_RESUMED");
        List<Map<String, Object>> events = events(viewerOnNodeB.nextOfType("ROOM_BACKFILL"));
        assertThat(events).extracting(event -> event.get("roomSeq")).containsExactly(1, 2);
        assertThat(events).extracting(event -> event.get("operationId")).doesNotHaveDuplicates();
        viewerOnNodeB.send(Map.of("type", "GET_DOCUMENT_STATE", "messageId", "state-after-node-switch", "roomId", fixture.roomId().toString(), "payload", Map.of()));
        assertThat(payload(viewerOnNodeB.nextOfType("DOCUMENT_STATE")))
                .containsEntry("contentText", "ab")
                .containsEntry("currentRevision", 2);
        assertThat(jdbcTemplate.queryForObject("select count(*) from syncforge_node_heartbeats where node_id in (?, ?)",
                Integer.class, simulatedNodeA, simulatedNodeB)).isEqualTo(2);

        TestSocket owner = TestSocket.connect(websocketUri(), fixture.ownerId(), "owner-a", "node-switch-owner", objectMapper);
        Map<String, Object> ownerJoin = join(owner, fixture.roomId().toString());
        String ownerToken = payload(ownerJoin).get("resumeToken").toString();
        owner.close();
        submitAck(editor, fixture.roomId().toString(), "switch-3", 3, 2, "NOOP", Map.of());
        submitAck(editor, fixture.roomId().toString(), "switch-4", 4, 3, "NOOP", Map.of());
        submitAck(editor, fixture.roomId().toString(), "switch-5", 5, 4, "NOOP", Map.of());

        TestSocket ownerOnNodeB = TestSocket.connect(websocketUri(), fixture.ownerId(), "owner-b", "node-switch-owner", objectMapper);
        ownerOnNodeB.send(Map.of("type", "RESUME_ROOM", "messageId", "too-far-node-b", "roomId", fixture.roomId().toString(),
                "payload", Map.of("resumeToken", ownerToken, "lastSeenRoomSeq", 0)));
        ownerOnNodeB.nextOfType("ROOM_RESUMED");
        Map<String, Object> resync = payload(ownerOnNodeB.nextOfType("RESYNC_REQUIRED"));
        assertThat(resync).containsEntry("reason", "client is too far behind");
        assertThat((Map<String, Object>) resync.get("documentState"))
                .containsEntry("contentText", "ab")
                .containsEntry("currentRevision", 5);
        editor.close();
        viewerOnNodeB.close();
        ownerOnNodeB.close();
    }

    private Map<String, Object> join(TestSocket socket, String roomId) throws Exception {
        socket.send(Map.of("type", "JOIN_ROOM", "messageId", "join", "roomId", roomId, "payload", Map.of()));
        return socket.nextOfType("JOINED_ROOM");
    }

    private void submitAck(TestSocket socket, String roomId, String operationId, long clientSeq, long baseRevision, String type, Map<String, Object> operation) throws Exception {
        socket.send(Map.of("type", "SUBMIT_OPERATION", "messageId", operationId, "roomId", roomId,
                "payload", Map.of("operationId", operationId, "clientSeq", clientSeq, "baseRevision", baseRevision,
                        "operationType", type, "operation", operation)));
        socket.nextOfType("OPERATION_ACK");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("payload");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> events(Map<String, Object> envelope) {
        return (List<Map<String, Object>>) payload(envelope).get("events");
    }
}
