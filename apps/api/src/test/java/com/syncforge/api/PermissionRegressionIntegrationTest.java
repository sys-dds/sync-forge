package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncforge.api.documentstate.application.DocumentStateService;
import com.syncforge.api.operation.application.OperationService;
import com.syncforge.api.operation.model.OperationSubmitResult;
import com.syncforge.api.operation.model.SubmitOperationCommand;
import com.syncforge.api.room.application.RoomPermissionService;
import com.syncforge.api.stream.application.RoomStreamKeyFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {
        "syncforge.redis.stream.enabled=true",
        "syncforge.redis.stream.maxlen=10000",
        "syncforge.rate-limit.operations-per-connection-per-second=100",
        "syncforge.rate-limit.operations-per-user-per-room-per-minute=200"
})
class PermissionRegressionIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    RoomPermissionService permissionService;

    @Autowired
    OperationService operationService;

    @Autowired
    DocumentStateService documentStateService;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    RoomStreamKeyFactory keyFactory;

    @Test
    void ownerEditorViewerAndNonMemberPermissionMatrixIsEnforced() throws Exception {
        Fixture fixture = fixture();
        redisTemplate.delete(keyFactory.roomStreamKey(fixture.roomId()));

        assertThat(permissionService.canJoin(fixture.roomId(), fixture.ownerId())).isTrue();
        assertThat(permissionService.canView(fixture.roomId(), fixture.ownerId())).isTrue();
        assertThat(permissionService.canEdit(fixture.roomId(), fixture.ownerId())).isTrue();
        assertThat(permissionService.canManageMembers(fixture.roomId(), fixture.ownerId())).isTrue();

        assertThat(permissionService.canJoin(fixture.roomId(), fixture.editorId())).isTrue();
        assertThat(permissionService.canView(fixture.roomId(), fixture.editorId())).isTrue();
        assertThat(permissionService.canEdit(fixture.roomId(), fixture.editorId())).isTrue();
        assertThat(permissionService.canManageMembers(fixture.roomId(), fixture.editorId())).isFalse();

        assertThat(permissionService.canJoin(fixture.roomId(), fixture.viewerId())).isTrue();
        assertThat(permissionService.canView(fixture.roomId(), fixture.viewerId())).isTrue();
        assertThat(permissionService.canEdit(fixture.roomId(), fixture.viewerId())).isFalse();
        assertThat(permissionService.canManageMembers(fixture.roomId(), fixture.viewerId())).isFalse();

        assertThat(permissionService.canJoin(fixture.roomId(), fixture.outsiderId())).isFalse();
        assertThat(permissionService.canView(fixture.roomId(), fixture.outsiderId())).isFalse();
        assertThat(permissionService.canEdit(fixture.roomId(), fixture.outsiderId())).isFalse();
        assertThat(permissionService.canManageMembers(fixture.roomId(), fixture.outsiderId())).isFalse();

        TestSocket owner = TestSocket.connect(websocketUri(), fixture.ownerId(), "owner-device", "owner-session", objectMapper);
        TestSocket editor = TestSocket.connect(websocketUri(), fixture.editorId(), "editor-device", "editor-session", objectMapper);
        TestSocket viewer = TestSocket.connect(websocketUri(), fixture.viewerId(), "viewer-device", "viewer-session", objectMapper);
        TestSocket outsider = TestSocket.connect(websocketUri(), fixture.outsiderId(), "outsider-device", "outsider-session", objectMapper);
        join(owner, fixture.roomId().toString());
        join(editor, fixture.roomId().toString());
        join(viewer, fixture.roomId().toString());
        owner.drain();
        editor.drain();
        viewer.drain();

        owner.send(operationMessage("perm-owner-edit", 1, 0, Map.of("position", 0, "text", "o"), fixture.roomId().toString()));
        assertThat(payload(owner.nextOfType("OPERATION_ACK"))).containsEntry("operationId", "perm-owner-edit");
        editor.send(operationMessage("perm-editor-edit", 1, 1, Map.of("position", 1, "text", "e"), fixture.roomId().toString()));
        assertThat(payload(editor.nextOfType("OPERATION_ACK"))).containsEntry("operationId", "perm-editor-edit");
        viewer.send(Map.of("type", "CURSOR_UPDATE", "messageId", "viewer-awareness", "roomId", fixture.roomId().toString(),
                "payload", Map.of("cursorPosition", 1)));
        assertThat(viewer.nextOfType("AWARENESS_UPDATED")).containsEntry("messageId", "viewer-awareness");
        viewer.send(operationMessage("perm-viewer-edit", 1, 2, Map.of("position", 2, "text", "v"), fixture.roomId().toString()));
        assertThat(payload(viewer.nextOfType("OPERATION_NACK"))).containsEntry("code", "EDIT_PERMISSION_REQUIRED");

        outsider.send(Map.of("type", "JOIN_ROOM", "messageId", "outsider-join", "roomId", fixture.roomId().toString(),
                "payload", Map.of()));
        assertThat(payload(outsider.nextOfType("ERROR"))).containsEntry("code", "ROOM_ACCESS_DENIED");
        outsider.send(Map.of("type", "GET_DOCUMENT_STATE", "messageId", "outsider-state", "roomId", fixture.roomId().toString(),
                "payload", Map.of()));
        assertThat(payload(outsider.nextOfType("ERROR"))).containsEntry("code", "CONNECTION_NOT_JOINED");
        outsider.send(Map.of("type", "CURSOR_UPDATE", "messageId", "outsider-awareness", "roomId", fixture.roomId().toString(),
                "payload", Map.of("cursorPosition", 1)));
        assertThat(payload(outsider.nextOfType("ERROR"))).containsEntry("code", "CONNECTION_NOT_JOINED");

        long seqBeforePermissionFailure = maxSeq(fixture.roomId());
        String contentBeforePermissionFailure = documentStateService.getOrInitialize(fixture.roomId()).contentText();
        OperationSubmitResult nonMemberEdit = operationService.submit(new SubmitOperationCommand(
                fixture.roomId(), fixture.outsiderId(), "outsider-connection", "outsider-session", "perm-outsider-edit",
                1L, seqBeforePermissionFailure, "TEXT_INSERT", Map.of("position", 2, "text", "x")));
        assertThat(nonMemberEdit.accepted()).isFalse();
        assertThat(nonMemberEdit.code()).isEqualTo("EDIT_PERMISSION_REQUIRED");
        assertThat(maxSeq(fixture.roomId())).isEqualTo(seqBeforePermissionFailure);
        assertThat(documentStateService.getOrInitialize(fixture.roomId()).contentText()).isEqualTo(contentBeforePermissionFailure);
        assertThat(redisTemplate.opsForStream().range(keyFactory.roomStreamKey(fixture.roomId()), Range.unbounded()))
                .hasSize(2);

        owner.close();
        editor.close();
        viewer.close();
        outsider.close();
    }

    @Test
    void removedMemberCannotSubmitOrResumeAndExistingConnectionIsBlockedOnNextSubmit() throws Exception {
        Fixture fixture = fixture();
        TestSocket editor = TestSocket.connect(websocketUri(), fixture.editorId(), "removed-device", "removed-session", objectMapper);
        Map<String, Object> joined = join(editor, fixture.roomId().toString());
        String resumeToken = payload(joined).get("resumeToken").toString();
        editor.drain();

        jdbcTemplate.update("""
                update room_memberships
                set status = 'REMOVED'
                where room_id = ? and user_id = ?
                """, fixture.roomId(), fixture.editorId());
        assertThat(permissionService.canJoin(fixture.roomId(), fixture.editorId())).isFalse();
        assertThat(permissionService.canEdit(fixture.roomId(), fixture.editorId())).isFalse();

        editor.send(operationMessage("perm-removed-submit", 1, 0, Map.of("position", 0, "text", "r"), fixture.roomId().toString()));
        assertThat(payload(editor.nextOfType("OPERATION_NACK"))).containsEntry("code", "EDIT_PERMISSION_REQUIRED");
        assertThat(maxSeq(fixture.roomId())).isZero();
        assertThat(documentStateService.getOrInitialize(fixture.roomId()).contentText()).isEmpty();

        editor.close();
        TestSocket resumed = TestSocket.connect(websocketUri(), fixture.editorId(), "removed-device-2", "removed-session", objectMapper);
        resumed.send(Map.of(
                "type", "RESUME_ROOM",
                "messageId", "removed-resume",
                "roomId", fixture.roomId().toString(),
                "payload", Map.of("resumeToken", resumeToken, "lastSeenRoomSeq", 0)));
        assertThat(payload(resumed.nextOfType("ERROR"))).containsEntry("code", "ROOM_ACCESS_DENIED");
        resumed.close();
    }

    private Map<String, Object> join(TestSocket socket, String roomId) throws Exception {
        socket.send(Map.of("type", "JOIN_ROOM", "messageId", "join-" + roomId, "roomId", roomId, "payload", Map.of()));
        return socket.nextOfType("JOINED_ROOM");
    }

    private Map<String, Object> operationMessage(
            String operationId,
            long clientSeq,
            long baseRevision,
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
                        "operationType", "TEXT_INSERT",
                        "operation", operation));
    }

    private long maxSeq(java.util.UUID roomId) {
        return jdbcTemplate.queryForObject("""
                select coalesce(max(room_seq), 0)
                from room_operations
                where room_id = ?
                """, Long.class, roomId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("payload");
    }
}
