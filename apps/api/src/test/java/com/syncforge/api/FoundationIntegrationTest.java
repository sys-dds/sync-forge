package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class FoundationIntegrationTest extends AbstractIntegrationTest {

    @Test
    void springContextStartsFlywayAppliesPostgresRedisAndPingHealthWork() {
        assertThat(POSTGRES.isRunning()).isTrue();
        assertThat(REDIS.isRunning()).isTrue();

        ResponseEntity<Map> ping = restTemplate.getForEntity(baseUrl + "/api/v1/system/ping", Map.class);
        assertThat(ping.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(ping.getBody()).containsEntry("service", "sync-forge-api").containsEntry("status", "ok");

        ResponseEntity<Map> health = restTemplate.getForEntity(baseUrl + "/actuator/health", Map.class);
        assertThat(health.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(health.getBody()).containsEntry("status", "UP");
    }

    @Test
    void dockerComposeConfigValidatesWherePractical() throws Exception {
        assumeTrue(commandAvailable("docker"), "docker is not available in this environment");
        Process process = new ProcessBuilder("docker", "compose", "-f", "../../infra/docker-compose/docker-compose.yml", "config")
                .directory(new java.io.File("."))
                .start();
        process.getInputStream().readAllBytes();
        process.getErrorStream().readAllBytes();
        int exit = process.waitFor();
        assertThat(exit).isZero();
    }

    private boolean commandAvailable(String command) {
        try {
            Process process = new ProcessBuilder(command, "--version").start();
            process.getInputStream().readAllBytes();
            process.getErrorStream().readAllBytes();
            return process.waitFor() == 0;
        } catch (IOException exception) {
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
