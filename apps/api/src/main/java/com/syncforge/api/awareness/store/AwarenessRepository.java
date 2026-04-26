package com.syncforge.api.awareness.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncforge.api.awareness.model.AwarenessState;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class AwarenessRepository {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RowMapper<AwarenessState> rowMapper = this::mapState;

    public AwarenessRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public AwarenessState upsertCursor(
            UUID roomId,
            UUID userId,
            String connectionId,
            Integer cursorPosition,
            Map<String, Object> metadata,
            OffsetDateTime now,
            OffsetDateTime expiresAt) {
        return upsert(roomId, userId, connectionId, "CURSOR", cursorPosition, null, null, metadata, now, expiresAt);
    }

    public AwarenessState upsertSelection(
            UUID roomId,
            UUID userId,
            String connectionId,
            Integer anchorPosition,
            Integer focusPosition,
            Map<String, Object> metadata,
            OffsetDateTime now,
            OffsetDateTime expiresAt) {
        return upsert(roomId, userId, connectionId, "SELECTION", null, anchorPosition, focusPosition, metadata, now, expiresAt);
    }

    public int expireStale(OffsetDateTime now) {
        return jdbcTemplate.update("""
                update room_awareness_states
                set status = 'EXPIRED'
                where status = 'ACTIVE' and expires_at <= ?
                """, now);
    }

    public List<AwarenessState> findActiveByRoom(UUID roomId) {
        return jdbcTemplate.query("""
                select a.id, a.room_id, a.user_id, a.connection_id, s.device_id, a.awareness_type,
                       a.cursor_position, a.anchor_position, a.focus_position, a.metadata_json,
                       a.updated_at, a.expires_at, a.status
                from room_awareness_states a
                join room_connection_sessions s on s.connection_id = a.connection_id
                where a.room_id = ? and a.status = 'ACTIVE' and a.expires_at > ?
                order by a.updated_at desc, a.id desc
                """, rowMapper, roomId, OffsetDateTime.now());
    }

    private AwarenessState upsert(
            UUID roomId,
            UUID userId,
            String connectionId,
            String awarenessType,
            Integer cursorPosition,
            Integer anchorPosition,
            Integer focusPosition,
            Map<String, Object> metadata,
            OffsetDateTime now,
            OffsetDateTime expiresAt) {
        return jdbcTemplate.queryForObject("""
                insert into room_awareness_states (
                    id, room_id, user_id, connection_id, awareness_type, cursor_position,
                    anchor_position, focus_position, metadata_json, updated_at, expires_at, status
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, 'ACTIVE')
                on conflict (room_id, connection_id, awareness_type) do update
                set user_id = excluded.user_id,
                    cursor_position = excluded.cursor_position,
                    anchor_position = excluded.anchor_position,
                    focus_position = excluded.focus_position,
                    metadata_json = excluded.metadata_json,
                    updated_at = excluded.updated_at,
                    expires_at = excluded.expires_at,
                    status = 'ACTIVE'
                returning id, room_id, user_id, connection_id,
                          (select device_id from room_connection_sessions where connection_id = room_awareness_states.connection_id) as device_id,
                          awareness_type, cursor_position, anchor_position, focus_position, metadata_json,
                          updated_at, expires_at, status
                """, rowMapper, UUID.randomUUID(), roomId, userId, connectionId, awarenessType, cursorPosition,
                anchorPosition, focusPosition, writeJson(metadata == null ? Map.of() : metadata), now, expiresAt);
    }

    private AwarenessState mapState(ResultSet rs, int rowNum) throws SQLException {
        return new AwarenessState(
                rs.getObject("id", UUID.class),
                rs.getObject("room_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("connection_id"),
                rs.getString("device_id"),
                rs.getString("awareness_type"),
                intOrNull(rs, "cursor_position"),
                intOrNull(rs, "anchor_position"),
                intOrNull(rs, "focus_position"),
                readMap(rs.getString("metadata_json")),
                rs.getObject("updated_at", OffsetDateTime.class),
                rs.getObject("expires_at", OffsetDateTime.class),
                rs.getString("status"));
    }

    private Integer intOrNull(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private Map<String, Object> readMap(String json) {
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to deserialize awareness metadata", exception);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize awareness metadata", exception);
        }
    }
}
