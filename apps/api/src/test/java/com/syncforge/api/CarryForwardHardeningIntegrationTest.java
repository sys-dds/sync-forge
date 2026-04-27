package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class CarryForwardHardeningIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void wrapperAndDockerComposeConfigAreValidWherePractical() throws Exception {
        assertThat(Files.exists(Path.of("mvnw"))).isTrue();
        assertThat(Files.exists(Path.of("mvnw.cmd"))).isTrue();

        Process process = new ProcessBuilder("docker", "compose", "-f",
                Path.of("..", "..", "infra", "docker-compose", "docker-compose.yml").toString(), "config")
                .start();
        String output = new String(process.getInputStream().readAllBytes())
                + new String(process.getErrorStream().readAllBytes());
        assertThat(process.waitFor()).as(output).isZero();
        assertThat(output).contains("syncforge-api", "syncforge-postgres", "syncforge-redis");
    }

    @Test
    void secondJoinIsRejectedWithoutCreatingExtraActiveSession() throws Exception {
        Fixture fixture = fixture();
        TestSocket client = TestSocket.connect(websocketUri(), fixture.ownerId(), "device-a", "client-a", objectMapper);
        client.send(Map.of("type", "JOIN_ROOM", "messageId", "join-1", "roomId", fixture.roomId().toString(), "payload", Map.of()));
        String connectionId = client.nextOfType("JOINED_ROOM").get("connectionId").toString();

        client.send(Map.of("type", "JOIN_ROOM", "messageId", "join-2", "roomId", fixture.roomId().toString(), "payload", Map.of()));
        Map<String, Object> rejected = client.nextOfType("ERROR");
        assertThat(payload(rejected)).containsEntry("code", "ALREADY_JOINED_ROOM");

        Integer connected = jdbcTemplate.queryForObject("""
                select count(*) from room_connection_sessions
                where room_id = ? and user_id = ? and status = 'CONNECTED'
                """, Integer.class, fixture.roomId(), fixture.ownerId());
        assertThat(connected).isEqualTo(1);
        assertThat(connectionId).isNotBlank();
        client.close();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("payload");
    }
}
