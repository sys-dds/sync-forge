package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class ConnectionRegistryIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void roomAndUserConnectionQueriesWorkAndMultipleDevicesAreSupported() throws Exception {
        Fixture fixture = fixture();

        TestSocket first = connect(fixture, "device-one", "client-session-one");
        TestSocket second = connect(fixture, "device-two", "client-session-two");

        Object[] roomConnections = restTemplate.getForObject(
                baseUrl + "/api/v1/rooms/" + fixture.roomId() + "/connections",
                Object[].class);
        Object[] userConnections = restTemplate.getForObject(
                baseUrl + "/api/v1/users/" + fixture.ownerId() + "/connections",
                Object[].class);

        List<Map<String, Object>> roomConnectionMaps = Arrays.stream(roomConnections).map(this::castMap).toList();
        List<Map<String, Object>> userConnectionMaps = Arrays.stream(userConnections).map(this::castMap).toList();
        assertThat(roomConnectionMaps)
                .filteredOn(connection -> "CONNECTED".equals(connection.get("status")))
                .hasSizeGreaterThanOrEqualTo(2);
        assertThat(userConnectionMaps)
                .extracting(connection -> connection.get("deviceId"))
                .contains("device-one", "device-two");

        String firstConnectionId = roomConnectionMaps.stream()
                .filter(connection -> "device-one".equals(connection.get("deviceId")))
                .findFirst()
                .orElseThrow()
                .get("connectionId")
                .toString();
        Integer eventCount = jdbcTemplate.queryForObject(
                "select count(*) from room_connection_events where connection_id = ?",
                Integer.class,
                firstConnectionId);
        assertThat(eventCount).isGreaterThanOrEqualTo(2);

        first.close();
        second.close();
    }

    @Test
    void errorsAreRecordedForJoinedConnections() throws Exception {
        Fixture fixture = fixture();
        TestSocket socket = TestSocket.connect(websocketUri(), fixture.ownerId(), null, null, new com.fasterxml.jackson.databind.ObjectMapper());
        socket.send(Map.of("type", "JOIN_ROOM", "messageId", "join", "roomId", fixture.roomId().toString(), "payload", Map.of()));
        String connectionId = socket.next().get("connectionId").toString();

        socket.send(Map.of("type", "BOGUS", "messageId", "bad", "roomId", fixture.roomId().toString(), "payload", Map.of()));
        Map<String, Object> error = socket.next();
        assertThat(error).containsEntry("type", "ERROR");

        Integer errors = jdbcTemplate.queryForObject(
                "select count(*) from room_connection_events where connection_id = ? and event_type = 'ERROR'",
                Integer.class,
                connectionId);
        assertThat(errors).isEqualTo(1);
        socket.close();
    }

    private TestSocket connect(Fixture fixture, String deviceId, String clientSessionId) throws Exception {
        TestSocket socket = TestSocket.connect(websocketUri(), fixture.ownerId(), deviceId, clientSessionId,
                new com.fasterxml.jackson.databind.ObjectMapper());
        socket.send(Map.of("type", "JOIN_ROOM", "messageId", deviceId, "roomId", fixture.roomId().toString(), "payload", Map.of()));
        assertThat(socket.next()).containsEntry("type", "JOINED_ROOM");
        return socket;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }
}
