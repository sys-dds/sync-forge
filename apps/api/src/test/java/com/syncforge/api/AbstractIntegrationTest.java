package com.syncforge.api;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("syncforge")
            .withUsername("syncforge")
            .withPassword("syncforge");

    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine")
            .withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    String baseUrl;

    @DynamicPropertySource
    static void dynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @BeforeEach
    void setBaseUrlAndCleanDatabase() {
        baseUrl = "http://localhost:" + port;
        jdbcTemplate.execute("""
                truncate table
                    cross_node_presence_states,
                    node_room_subscriptions,
                    syncforge_node_heartbeats,
                    room_stream_offsets,
                    websocket_session_quarantines,
                    websocket_slow_consumer_events,
                    room_backpressure_states,
                    room_rate_limit_events,
                    websocket_connection_flow_controls,
                    room_backfill_requests,
                    room_client_offsets,
                    room_resume_tokens,
                    document_snapshots,
                    room_conflict_resolution_traces,
                    document_state_rebuild_runs,
                    document_live_states,
                    room_operations,
                    room_operation_attempts,
                    room_sequence_counters,
                    room_awareness_states,
                    room_user_presence,
                    room_presence_connections,
                    room_connection_events,
                    room_connection_sessions,
                    room_memberships,
                    rooms,
                    documents,
                    users,
                    workspaces
                cascade
                """);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> postMap(String path, Map<String, Object> body) {
        ResponseEntity<Map> response = restTemplate.postForEntity(baseUrl + path, body, Map.class);
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new AssertionError("POST " + path + " failed: " + response.getStatusCode() + " " + response.getBody());
        }
        return response.getBody();
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> getMap(String path) {
        ResponseEntity<Map> response = restTemplate.getForEntity(baseUrl + path, Map.class);
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new AssertionError("GET " + path + " failed: " + response.getStatusCode() + " " + response.getBody());
        }
        return response.getBody();
    }

    Fixture fixture() {
        String suffix = UUID.randomUUID().toString();
        Map<String, Object> workspace = postMap("/api/v1/workspaces",
                Map.of("workspaceKey", "workspace-" + suffix, "name", "Workspace " + suffix));
        Map<String, Object> owner = postMap("/api/v1/users",
                Map.of("externalUserKey", "owner-" + suffix, "displayName", "Owner"));
        Map<String, Object> editor = postMap("/api/v1/users",
                Map.of("externalUserKey", "editor-" + suffix, "displayName", "Editor"));
        Map<String, Object> viewer = postMap("/api/v1/users",
                Map.of("externalUserKey", "viewer-" + suffix, "displayName", "Viewer"));
        Map<String, Object> outsider = postMap("/api/v1/users",
                Map.of("externalUserKey", "outsider-" + suffix, "displayName", "Outsider"));
        String workspaceId = workspace.get("id").toString();
        Map<String, Object> document = postMap("/api/v1/workspaces/" + workspaceId + "/documents",
                Map.of("documentKey", "doc-" + suffix, "title", "Doc"));
        String documentId = document.get("id").toString();
        Map<String, Object> room = postMap("/api/v1/workspaces/" + workspaceId + "/documents/" + documentId + "/rooms",
                Map.of("roomKey", "room-" + suffix, "roomType", "DOCUMENT"));
        String roomId = room.get("id").toString();
        postMap("/api/v1/rooms/" + roomId + "/memberships", Map.of("userId", owner.get("id"), "role", "OWNER"));
        postMap("/api/v1/rooms/" + roomId + "/memberships", Map.of("userId", editor.get("id"), "role", "EDITOR"));
        postMap("/api/v1/rooms/" + roomId + "/memberships", Map.of("userId", viewer.get("id"), "role", "VIEWER"));
        return new Fixture(
                UUID.fromString(workspaceId),
                UUID.fromString(documentId),
                UUID.fromString(roomId),
                UUID.fromString(owner.get("id").toString()),
                UUID.fromString(editor.get("id").toString()),
                UUID.fromString(viewer.get("id").toString()),
                UUID.fromString(outsider.get("id").toString()));
    }

    URI websocketUri() {
        return URI.create("ws://localhost:" + port + "/ws/rooms");
    }

    HttpEntity<Map<String, Object>> entity(Map<String, Object> body) {
        return new HttpEntity<>(body, new HttpHeaders());
    }

    record Fixture(UUID workspaceId, UUID documentId, UUID roomId, UUID ownerId, UUID editorId, UUID viewerId, UUID outsiderId) {
    }
}
