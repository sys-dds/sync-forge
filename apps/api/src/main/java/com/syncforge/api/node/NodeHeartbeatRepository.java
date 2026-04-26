package com.syncforge.api.node;

import java.time.OffsetDateTime;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class NodeHeartbeatRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public NodeHeartbeatRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public NodeHeartbeat upsertActive(String nodeId, Map<String, Object> metadata) {
        return jdbcTemplate.queryForObject("""
                insert into syncforge_node_heartbeats (node_id, status, metadata_json)
                values (?, 'ACTIVE', ?::jsonb)
                on conflict (node_id) do update
                set last_seen_at = now(),
                    status = 'ACTIVE',
                    metadata_json = excluded.metadata_json
                returning node_id, started_at, last_seen_at, status
                """, (rs, rowNum) -> new NodeHeartbeat(
                        rs.getString("node_id"),
                        rs.getObject("started_at", OffsetDateTime.class),
                        rs.getObject("last_seen_at", OffsetDateTime.class),
                        rs.getString("status")),
                nodeId,
                toJson(metadata));
    }

    public int markStale(long ttlSeconds) {
        return jdbcTemplate.update("""
                update syncforge_node_heartbeats
                set status = 'STALE'
                where status = 'ACTIVE'
                  and last_seen_at < now() - (? * interval '1 second')
                """, ttlSeconds);
    }

    public record NodeHeartbeat(String nodeId, OffsetDateTime startedAt, OffsetDateTime lastSeenAt, String status) {
    }

    private String toJson(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata == null ? Map.of() : metadata);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("metadata must be JSON serializable", exception);
        }
    }
}
