package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

    @Test
    void copiedProjectLeftoversAreNotPresentInSourceAndConfig() throws Exception {
        List<String> forbidden = List.of(
                "match" + "graph",
                term(107, 97, 121, 108, 101, 100, 103, 101, 114),
                "distributed" + "-" + "link" + "-" + "platform",
                "link" + "-" + "platform",
                "Deploy" + "Forge",
                "Market" + "Grid",
                "com.match" + "graph",
                term(99, 111, 109, 46, 107, 97, 121, 108, 101, 100, 103, 101, 114),
                "rank" + "ing",
                "recommend" + "ation",
                "pay" + "ment",
                "market" + "place");
        for (String term : forbidden) {
            Process process = new ProcessBuilder("rg", "-n", "-i", term,
                    "README.md",
                    ".env.example",
                    "apps/api",
                    "infra/docker-compose")
                    .directory(Path.of("..", "..").toFile())
                    .start();
            String output = new String(process.getInputStream().readAllBytes())
                    + new String(process.getErrorStream().readAllBytes());
            assertThat(process.waitFor()).as(output).isEqualTo(1);
        }
    }

    private String term(int... codePoints) {
        StringBuilder builder = new StringBuilder();
        for (int codePoint : codePoints) {
            builder.append((char) codePoint);
        }
        return builder.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("payload");
    }
}
