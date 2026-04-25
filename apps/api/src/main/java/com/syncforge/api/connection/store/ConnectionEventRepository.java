package com.syncforge.api.connection.store;

import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ConnectionEventRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ConnectionEventRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void record(UUID roomId, UUID userId, String connectionId, String eventType, Map<String, ?> eventJson) {
        try {
            jdbcTemplate.update("""
                    insert into room_connection_events (id, room_id, user_id, connection_id, event_type, event_json)
                    values (?, ?, ?, ?, ?, cast(? as jsonb))
                    """, UUID.randomUUID(), roomId, userId, connectionId, eventType, objectMapper.writeValueAsString(eventJson));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize connection event", exception);
        }
    }

    public int countByConnectionId(String connectionId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from room_connection_events where connection_id = ?",
                Integer.class,
                connectionId);
        return count == null ? 0 : count;
    }
}
