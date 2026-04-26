package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncforge.api.backpressure.application.ConnectionFlowControlService;
import com.syncforge.api.backpressure.application.SlowConsumerService;
import com.syncforge.api.node.NodeIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class WebSocketFlowControlIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    ConnectionFlowControlService flowControlService;

    @Autowired
    SlowConsumerService slowConsumerService;

    @Autowired
    NodeIdentity nodeIdentity;

    @Test
    void boundedQueueStatusSlowWarningAndClosedStateAreObservable() throws Exception {
        Fixture fixture = fixture();
        TestSocket editor = TestSocket.connect(websocketUri(), fixture.editorId(), "flow-device", "flow-session", objectMapper);
        Map<String, Object> joined = join(editor, fixture.roomId().toString());
        String connectionId = joined.get("connectionId").toString();

        Map<String, Object> flow = getMap("/api/v1/rooms/" + fixture.roomId() + "/flow/connections/" + connectionId);
        assertThat(flow)
                .containsEntry("connectionId", connectionId)
                .containsEntry("status", "ACTIVE")
                .containsEntry("maxQueuedMessages", 100);
        assertThat((Integer) flow.get("queuedMessages")).isBetween(0, 100);

        flowControlService.updateQueued(connectionId, 80);
        slowConsumerService.warn(fixture.roomId(), fixture.editorId(), connectionId, nodeIdentity.nodeId(), 80, 80);
        assertThat(getMap("/api/v1/rooms/" + fixture.roomId() + "/flow/connections/" + connectionId))
                .containsEntry("status", "SLOW")
                .containsEntry("queuedMessages", 80)
                .containsEntry("maxQueuedMessages", 100);
        assertThat(getList("/api/v1/rooms/" + fixture.roomId() + "/slow-consumers"))
                .anySatisfy(row -> assertThat(row)
                        .containsEntry("connectionId", connectionId)
                        .containsEntry("decision", "WARNED"));

        editor.close();
        assertThat(getMap("/api/v1/rooms/" + fixture.roomId() + "/flow/connections/" + connectionId))
                .containsEntry("status", "CLOSED")
                .containsEntry("queuedMessages", 0);
    }

    private Map<String, Object> join(TestSocket socket, String roomId) throws Exception {
        socket.send(Map.of("type", "JOIN_ROOM", "messageId", "join-" + roomId, "roomId", roomId, "payload", Map.of()));
        return socket.nextOfType("JOINED_ROOM");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getList(String path) {
        return restTemplate.getForObject(baseUrl + path, List.class);
    }
}
