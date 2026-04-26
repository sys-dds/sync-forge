package com.syncforge.api.presence.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncforge.api.presence.model.PresenceConnection;
import com.syncforge.api.presence.model.UserPresence;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class PresenceRepository {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RowMapper<PresenceConnection> connectionRowMapper = this::mapConnection;
    private final RowMapper<UserPresence> userPresenceRowMapper = this::mapUserPresence;

    public PresenceRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void upsertPresent(
            UUID roomId,
            UUID userId,
            String connectionId,
            String websocketSessionId,
            String deviceId,
            String clientSessionId,
            OffsetDateTime now,
            OffsetDateTime expiresAt) {
        jdbcTemplate.update("""
                insert into room_presence_connections (
                    id, room_id, user_id, connection_id, websocket_session_id, device_id, client_session_id,
                    status, joined_at, last_seen_at, expires_at, left_at, leave_reason, metadata_json
                )
                values (?, ?, ?, ?, ?, ?, ?, 'PRESENT', ?, ?, ?, null, null, '{}'::jsonb)
                on conflict (connection_id) do update
                set status = 'PRESENT',
                    last_seen_at = excluded.last_seen_at,
                    expires_at = excluded.expires_at,
                    left_at = null,
                    leave_reason = null,
                    websocket_session_id = excluded.websocket_session_id,
                    device_id = excluded.device_id,
                    client_session_id = excluded.client_session_id
                """, UUID.randomUUID(), roomId, userId, connectionId, websocketSessionId, deviceId, clientSessionId,
                now, now, expiresAt);
    }

    public void markLeft(String connectionId, String reason, OffsetDateTime now) {
        jdbcTemplate.update("""
                update room_presence_connections
                set status = 'LEFT', left_at = ?, leave_reason = ?, last_seen_at = ?
                where connection_id = ? and status = 'PRESENT'
                """, now, reason, now, connectionId);
    }

    public void refresh(String connectionId, OffsetDateTime now, OffsetDateTime expiresAt) {
        jdbcTemplate.update("""
                update room_presence_connections
                set last_seen_at = ?, expires_at = ?
                where connection_id = ? and status = 'PRESENT'
                """, now, expiresAt, connectionId);
    }

    public List<PresenceConnection> expireStale(OffsetDateTime now) {
        return jdbcTemplate.query("""
                update room_presence_connections
                set status = 'EXPIRED', left_at = ?, leave_reason = 'TTL_EXPIRED'
                where status = 'PRESENT' and expires_at <= ?
                returning id, room_id, user_id, connection_id, websocket_session_id, device_id, client_session_id,
                          status, joined_at, last_seen_at, expires_at, left_at, leave_reason, metadata_json
                """, connectionRowMapper, now, now);
    }

    public List<PresenceConnection> findConnectionsByRoom(UUID roomId) {
        return jdbcTemplate.query("""
                select id, room_id, user_id, connection_id, websocket_session_id, device_id, client_session_id,
                       status, joined_at, last_seen_at, expires_at, left_at, leave_reason, metadata_json
                from room_presence_connections
                where room_id = ?
                order by joined_at desc, id desc
                """, connectionRowMapper, roomId);
    }

    public List<UserPresence> findUserPresenceByRoom(UUID roomId) {
        return jdbcTemplate.query("""
                select room_id, user_id, status, active_connection_count, active_device_ids, last_seen_at, updated_at
                from room_user_presence
                where room_id = ?
                order by status desc, last_seen_at desc, user_id
                """, userPresenceRowMapper, roomId);
    }

    public void recomputeUserPresence(UUID roomId, UUID userId, OffsetDateTime now) {
        List<PresenceConnection> active = jdbcTemplate.query("""
                select id, room_id, user_id, connection_id, websocket_session_id, device_id, client_session_id,
                       status, joined_at, last_seen_at, expires_at, left_at, leave_reason, metadata_json
                from room_presence_connections
                where room_id = ? and user_id = ? and status = 'PRESENT' and expires_at > ?
                order by last_seen_at desc
                """, connectionRowMapper, roomId, userId, now);
        List<String> devices = active.stream()
                .map(PresenceConnection::deviceId)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
        OffsetDateTime lastSeen = active.stream()
                .map(PresenceConnection::lastSeenAt)
                .max(OffsetDateTime::compareTo)
                .orElseGet(() -> lastSeenForRoomUser(roomId, userId, now));
        String status = active.isEmpty() ? "OFFLINE" : "ONLINE";
        jdbcTemplate.update("""
                insert into room_user_presence (
                    room_id, user_id, status, active_connection_count, active_device_ids, last_seen_at, updated_at
                )
                values (?, ?, ?, ?, cast(? as jsonb), ?, ?)
                on conflict (room_id, user_id) do update
                set status = excluded.status,
                    active_connection_count = excluded.active_connection_count,
                    active_device_ids = excluded.active_device_ids,
                    last_seen_at = excluded.last_seen_at,
                    updated_at = excluded.updated_at
                """, roomId, userId, status, active.size(), writeJson(devices), lastSeen, now);
    }

    private OffsetDateTime lastSeenForRoomUser(UUID roomId, UUID userId, OffsetDateTime fallback) {
        OffsetDateTime value = jdbcTemplate.query("""
                select last_seen_at
                from room_presence_connections
                where room_id = ? and user_id = ?
                order by last_seen_at desc
                limit 1
                """, rs -> rs.next() ? rs.getObject("last_seen_at", OffsetDateTime.class) : null, roomId, userId);
        return value == null ? fallback : value;
    }

    private PresenceConnection mapConnection(ResultSet rs, int rowNum) throws SQLException {
        return new PresenceConnection(
                rs.getObject("id", UUID.class),
                rs.getObject("room_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("connection_id"),
                rs.getString("websocket_session_id"),
                rs.getString("device_id"),
                rs.getString("client_session_id"),
                rs.getString("status"),
                rs.getObject("joined_at", OffsetDateTime.class),
                rs.getObject("last_seen_at", OffsetDateTime.class),
                rs.getObject("expires_at", OffsetDateTime.class),
                rs.getObject("left_at", OffsetDateTime.class),
                rs.getString("leave_reason"),
                readMap(rs.getString("metadata_json")));
    }

    private UserPresence mapUserPresence(ResultSet rs, int rowNum) throws SQLException {
        return new UserPresence(
                rs.getObject("room_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("status"),
                rs.getInt("active_connection_count"),
                readStringList(rs.getString("active_device_ids")),
                rs.getObject("last_seen_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private Map<String, Object> readMap(String json) {
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to deserialize presence metadata", exception);
        }
    }

    private List<String> readStringList(String json) {
        try {
            return new ArrayList<>(objectMapper.readValue(json, STRING_LIST_TYPE));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to deserialize presence devices", exception);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize presence JSON", exception);
        }
    }
}
