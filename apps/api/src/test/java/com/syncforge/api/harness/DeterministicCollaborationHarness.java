package com.syncforge.api.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.syncforge.api.documentstate.application.DocumentStateService;
import com.syncforge.api.documentstate.model.DocumentLiveState;
import com.syncforge.api.operation.application.OperationService;
import com.syncforge.api.operation.model.OperationSubmitResult;
import com.syncforge.api.operation.model.SubmitOperationCommand;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

public class DeterministicCollaborationHarness {
    private final TestRestTemplate restTemplate;
    private final String baseUrl;
    private final JdbcTemplate jdbcTemplate;
    private final OperationService operationService;
    private final DocumentStateService documentStateService;

    public DeterministicCollaborationHarness(
            TestRestTemplate restTemplate,
            String baseUrl,
            JdbcTemplate jdbcTemplate,
            OperationService operationService,
            DocumentStateService documentStateService) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
        this.jdbcTemplate = jdbcTemplate;
        this.operationService = operationService;
        this.documentStateService = documentStateService;
    }

    public ScenarioResult run(CollaborationScenario scenario) {
        Fixture fixture = createFixture(scenario.seed());
        List<Map<String, Object>> outcomes = new ArrayList<>();
        int accepted = 0;
        int rejected = 0;
        for (ScriptedOperation operation : scenario.operations()) {
            ScriptedClient client = fixture.clients().get(operation.clientKey());
            OperationSubmitResult result = operationService.submit(new SubmitOperationCommand(
                    fixture.roomId(),
                    client.userId(),
                    operation.clientKey() + "-connection",
                    operation.clientKey() + "-session",
                    operation.operationId(),
                    operation.clientSeq(),
                    operation.baseRevision(),
                    operation.operationType(),
                    operation.operation()));
            Map<String, Object> outcome = new LinkedHashMap<>();
            outcome.put("operationId", operation.operationId());
            outcome.put("accepted", result.accepted());
            outcome.put("duplicate", result.duplicate());
            outcome.put("roomSeq", result.roomSeq());
            outcome.put("revision", result.revision());
            outcome.put("code", result.code());
            outcomes.add(outcome);
            if (result.accepted()) {
                accepted++;
            } else {
                rejected++;
            }
        }
        DocumentLiveState state = documentStateService.getOrInitialize(fixture.roomId());
        assertReplayEqualsLive(fixture.roomId());
        return new ScenarioResult(scenario.seed(), state.contentText(), state.currentRevision(), state.currentRoomSeq(),
                accepted, rejected, outcomes);
    }

    public void assertReplayEqualsLive(UUID roomId) {
        assertThat(documentStateService.verifyFullReplayEquivalence(roomId))
                .as("seeded scenario replay/live equivalence for room %s", roomId)
                .isTrue();
    }

    public Fixture createFixture(long seed) {
        String suffix = "harness-" + seed + "-" + UUID.randomUUID();
        Map<String, Object> workspace = postMap("/api/v1/workspaces",
                Map.of("workspaceKey", suffix, "name", "Harness " + seed));
        Map<String, Object> owner = postMap("/api/v1/users",
                Map.of("externalUserKey", suffix + "-owner", "displayName", "Owner"));
        Map<String, Object> editor = postMap("/api/v1/users",
                Map.of("externalUserKey", suffix + "-editor", "displayName", "Editor"));
        Map<String, Object> viewer = postMap("/api/v1/users",
                Map.of("externalUserKey", suffix + "-viewer", "displayName", "Viewer"));
        Map<String, Object> outsider = postMap("/api/v1/users",
                Map.of("externalUserKey", suffix + "-outsider", "displayName", "Outsider"));
        String workspaceId = workspace.get("id").toString();
        Map<String, Object> document = postMap("/api/v1/workspaces/" + workspaceId + "/documents",
                Map.of("documentKey", suffix + "-doc", "title", "Harness Doc"));
        String documentId = document.get("id").toString();
        Map<String, Object> room = postMap("/api/v1/workspaces/" + workspaceId + "/documents/" + documentId + "/rooms",
                Map.of("roomKey", suffix + "-room", "roomType", "DOCUMENT"));
        UUID roomId = UUID.fromString(room.get("id").toString());
        UUID ownerId = UUID.fromString(owner.get("id").toString());
        UUID editorId = UUID.fromString(editor.get("id").toString());
        UUID viewerId = UUID.fromString(viewer.get("id").toString());
        UUID outsiderId = UUID.fromString(outsider.get("id").toString());
        postMap("/api/v1/rooms/" + roomId + "/memberships", Map.of("userId", ownerId, "role", "OWNER"));
        postMap("/api/v1/rooms/" + roomId + "/memberships", Map.of("userId", editorId, "role", "EDITOR"));
        postMap("/api/v1/rooms/" + roomId + "/memberships", Map.of("userId", viewerId, "role", "VIEWER"));
        return new Fixture(
                UUID.fromString(workspaceId),
                UUID.fromString(documentId),
                roomId,
                Map.of(
                        "owner", new ScriptedClient("owner", ownerId, "OWNER"),
                        "editor", new ScriptedClient("editor", editorId, "EDITOR"),
                        "viewer", new ScriptedClient("viewer", viewerId, "VIEWER"),
                        "outsider", new ScriptedClient("outsider", outsiderId, "NON_MEMBER")));
    }

    public long operationRowCount(UUID roomId) {
        return jdbcTemplate.queryForObject("select count(*) from room_operations where room_id = ?", Long.class, roomId);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> postMap(String path, Map<String, Object> body) {
        ResponseEntity<Map> response = restTemplate.postForEntity(baseUrl + path, body, Map.class);
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new AssertionError("POST " + path + " failed: " + response.getStatusCode() + " " + response.getBody());
        }
        return response.getBody();
    }

    public record Fixture(UUID workspaceId, UUID documentId, UUID roomId, Map<String, ScriptedClient> clients) {
    }
}
