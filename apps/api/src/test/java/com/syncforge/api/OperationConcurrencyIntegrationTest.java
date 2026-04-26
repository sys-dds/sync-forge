package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class OperationConcurrencyIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    ObjectMapper objectMapper;

    @Test
    void concurrentStaleSubmitsKeepAcceptedRoomSeqUniqueGaplessAndDeterministic() throws Exception {
        Fixture fixture = fixture();
        TestSocket owner = TestSocket.connect(websocketUri(), fixture.ownerId(), "owner-device", "owner-session", objectMapper);
        TestSocket editor = TestSocket.connect(websocketUri(), fixture.editorId(), "editor-device", "editor-session", objectMapper);
        join(owner, fixture.roomId().toString());
        join(editor, fixture.roomId().toString());
        owner.drain();
        editor.drain();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        executor.submit(() -> sendAfterStart(owner, fixture.roomId().toString(), "concurrent-owner", ready, start));
        executor.submit(() -> sendAfterStart(editor, fixture.roomId().toString(), "concurrent-editor", ready, start));
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        Map<String, Object> ownerResult = owner.nextMatching(this::terminalOperationResult, "owner operation result");
        Map<String, Object> editorResult = editor.nextMatching(this::terminalOperationResult, "editor operation result");
        List<String> resultTypes = List.of(ownerResult.get("type").toString(), editorResult.get("type").toString());
        assertThat(resultTypes).containsExactlyInAnyOrder("OPERATION_ACK", "OPERATION_ACK");

        List<Map<String, Object>> operations = getList("/api/v1/rooms/" + fixture.roomId() + "/operations");
        assertThat(operations).hasSize(2);
        assertThat(operations.stream().map(row -> (Integer) row.get("roomSeq")).toList()).containsExactly(1, 2);
        assertThat(getMap("/api/v1/rooms/" + fixture.roomId() + "/sequence"))
                .containsEntry("currentRoomSeq", 2)
                .containsEntry("currentRevision", 2);

        owner.send(operationMessage("ordered-3", 2, 2, "NOOP", Map.of(), fixture.roomId().toString()));
        assertThat(payload(owner.nextOfType("OPERATION_ACK"))).containsEntry("roomSeq", 3).containsEntry("revision", 3);
        editor.send(operationMessage("ordered-4", 2, 3, "NOOP", Map.of(), fixture.roomId().toString()));
        assertThat(payload(editor.nextOfType("OPERATION_ACK"))).containsEntry("roomSeq", 4).containsEntry("revision", 4);

        List<Integer> acceptedSeqs = getList("/api/v1/rooms/" + fixture.roomId() + "/operations").stream()
                .map(row -> (Integer) row.get("roomSeq"))
                .toList();
        assertThat(acceptedSeqs).containsExactly(1, 2, 3, 4);
        owner.close();
        editor.close();
    }

    private void sendAfterStart(TestSocket socket, String roomId, String operationId, CountDownLatch ready, CountDownLatch start) {
        try {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            socket.send(operationMessage(operationId, 1, 0, "NOOP", Map.of(), roomId));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private boolean terminalOperationResult(Map<String, Object> message) {
        return "OPERATION_ACK".equals(message.get("type")) || "OPERATION_NACK".equals(message.get("type"));
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

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getList(String path) {
        return restTemplate.getForObject(baseUrl + path, List.class);
    }
}
