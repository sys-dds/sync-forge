package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class OperationProtocolIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    ObjectMapper objectMapper;

    @Test
    void editorSubmitPersistsAttemptLogSequenceAndBroadcastsAppliedOperation() throws Exception {
        Fixture fixture = fixture();
        TestSocket editor = TestSocket.connect(websocketUri(), fixture.editorId(), "editor-device", "editor-session", objectMapper);
        TestSocket owner = TestSocket.connect(websocketUri(), fixture.ownerId(), "owner-device", "owner-session", objectMapper);
        join(editor, fixture.roomId().toString());
        join(owner, fixture.roomId().toString());
        editor.drain();
        owner.drain();

        editor.send(operationMessage("op-editor-1", 1, 0, "TEXT_INSERT", Map.of("position", 0, "text", "hello"), fixture.roomId().toString()));
        Map<String, Object> ack = editor.nextOfType("OPERATION_ACK");
        assertThat(payload(ack)).containsEntry("operationId", "op-editor-1")
                .containsEntry("clientSeq", 1)
                .containsEntry("roomSeq", 1)
                .containsEntry("revision", 1)
                .containsEntry("duplicate", false);
        Map<String, Object> applied = owner.nextOfType("OPERATION_APPLIED");
        assertThat(payload(applied)).containsEntry("operationId", "op-editor-1")
                .containsEntry("roomSeq", 1)
                .containsEntry("revision", 1)
                .containsEntry("operationType", "TEXT_INSERT");

        List<Map<String, Object>> operations = getList("/api/v1/rooms/" + fixture.roomId() + "/operations");
        assertThat(operations).hasSize(1);
        assertThat(operations.getFirst()).containsEntry("operationId", "op-editor-1").containsEntry("roomSeq", 1);
        List<Map<String, Object>> attempts = getList("/api/v1/rooms/" + fixture.roomId() + "/operation-attempts");
        assertThat(attempts).anySatisfy(row -> assertThat(row).containsEntry("operationId", "op-editor-1").containsEntry("outcome", "ACCEPTED"));
        assertThat(getMap("/api/v1/rooms/" + fixture.roomId() + "/sequence")).containsEntry("currentRoomSeq", 1).containsEntry("currentRevision", 1);
        editor.close();
        owner.close();
    }

    @Test
    void ownerSubmitIsAccepted() throws Exception {
        Fixture fixture = fixture();
        TestSocket owner = TestSocket.connect(websocketUri(), fixture.ownerId(), "owner-device", "owner-session", objectMapper);
        join(owner, fixture.roomId().toString());
        owner.send(operationMessage("op-owner-1", 1, 0, "NOOP", Map.of("note", "owner"), fixture.roomId().toString()));
        assertThat(payload(owner.nextOfType("OPERATION_ACK"))).containsEntry("operationId", "op-owner-1").containsEntry("revision", 1);
        owner.close();
    }

    @Test
    void viewerSubmitIsRejectedAndDoesNotIncrementSequence() throws Exception {
        Fixture fixture = fixture();
        TestSocket viewer = TestSocket.connect(websocketUri(), fixture.viewerId(), "viewer-device", "viewer-session", objectMapper);
        join(viewer, fixture.roomId().toString());
        viewer.send(operationMessage("op-viewer-1", 1, 0, "TEXT_INSERT", Map.of("position", 0, "text", "no"), fixture.roomId().toString()));
        Map<String, Object> nack = viewer.nextOfType("OPERATION_NACK");
        assertThat(payload(nack)).containsEntry("code", "EDIT_PERMISSION_REQUIRED");
        assertThat(getMap("/api/v1/rooms/" + fixture.roomId() + "/sequence")).containsEntry("currentRoomSeq", 0).containsEntry("currentRevision", 0);
        assertThat(getList("/api/v1/rooms/" + fixture.roomId() + "/operation-attempts"))
                .anySatisfy(row -> assertThat(row).containsEntry("operationId", "op-viewer-1").containsEntry("outcome", "REJECTED"));
        viewer.close();
    }

    @Test
    void staleBaseRevisionDuplicateAndDuplicateConflictAreHandledWithoutDoubleIncrement() throws Exception {
        Fixture fixture = fixture();
        TestSocket editor = TestSocket.connect(websocketUri(), fixture.editorId(), "editor-device", "editor-session", objectMapper);
        join(editor, fixture.roomId().toString());

        editor.send(operationMessage("op-dup-1", 1, 0, "TEXT_INSERT", Map.of("position", 0, "text", "a"), fixture.roomId().toString()));
        assertThat(payload(editor.nextOfType("OPERATION_ACK"))).containsEntry("roomSeq", 1).containsEntry("revision", 1);

        editor.send(operationMessage("op-stale-1", 2, 0, "TEXT_INSERT", Map.of("position", 1, "text", "b"), fixture.roomId().toString()));
        Map<String, Object> stale = editor.nextOfType("OPERATION_NACK");
        assertThat(payload(stale)).containsEntry("code", "STALE_BASE_REVISION").containsEntry("currentRevision", 1);

        editor.send(operationMessage("op-dup-1", 1, 0, "TEXT_INSERT", Map.of("position", 0, "text", "a"), fixture.roomId().toString()));
        Map<String, Object> duplicate = editor.nextOfType("OPERATION_ACK");
        assertThat(payload(duplicate)).containsEntry("duplicate", true).containsEntry("roomSeq", 1).containsEntry("revision", 1);

        editor.send(operationMessage("op-dup-1", 1, 0, "TEXT_INSERT", Map.of("position", 9, "text", "different"), fixture.roomId().toString()));
        assertThat(payload(editor.nextOfType("OPERATION_NACK"))).containsEntry("code", "DUPLICATE_OPERATION_CONFLICT");

        assertThat(getMap("/api/v1/rooms/" + fixture.roomId() + "/sequence")).containsEntry("currentRoomSeq", 1).containsEntry("currentRevision", 1);
        assertThat(getList("/api/v1/rooms/" + fixture.roomId() + "/operations")).hasSize(1);
        editor.close();
    }

    @Test
    void unsupportedTypeAndInvalidSequencingAreRejectedClearly() throws Exception {
        Fixture fixture = fixture();
        TestSocket editor = TestSocket.connect(websocketUri(), fixture.editorId(), "editor-device", "editor-session", objectMapper);
        join(editor, fixture.roomId().toString());

        editor.send(operationMessage("op-unsupported", 1, 0, "IMAGE_EMBED", Map.of("src", "x"), fixture.roomId().toString()));
        assertThat(payload(editor.nextOfType("OPERATION_NACK"))).containsEntry("code", "UNSUPPORTED_OPERATION_TYPE");
        assertThat(getList("/api/v1/rooms/" + fixture.roomId() + "/operation-attempts"))
                .anySatisfy(row -> assertThat(row).containsEntry("operationId", "op-unsupported").containsEntry("outcome", "REJECTED"));

        editor.send(operationMessage("op-invalid-seq", 0, 0, "NOOP", Map.of(), fixture.roomId().toString()));
        assertThat(payload(editor.nextOfType("OPERATION_NACK"))).containsEntry("code", "INVALID_CLIENT_SEQ");
        editor.send(operationMessage("op-invalid-revision", 1, -1, "NOOP", Map.of(), fixture.roomId().toString()));
        assertThat(payload(editor.nextOfType("OPERATION_NACK"))).containsEntry("code", "INVALID_BASE_REVISION");
        assertThat(getList("/api/v1/rooms/" + fixture.roomId() + "/operations")).isEmpty();
        editor.close();
    }

    private void join(TestSocket socket, String roomId) throws Exception {
        socket.send(Map.of("type", "JOIN_ROOM", "messageId", "join", "roomId", roomId, "payload", Map.of()));
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
