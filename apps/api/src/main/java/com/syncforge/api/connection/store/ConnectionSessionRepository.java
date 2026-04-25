package com.syncforge.api.connection.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.syncforge.api.connection.model.ConnectionSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ConnectionSessionRepository {
    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<ConnectionSession> rowMapper = this::mapSession;

    public ConnectionSessionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void createConnected(
            UUID roomId,
            UUID userId,
            String connectionId,
            String websocketSessionId,
            String deviceId,
            String clientSessionId) {
        jdbcTemplate.update("""
                insert into room_connection_sessions (
                    id, room_id, user_id, connection_id, websocket_session_id, device_id, client_session_id, status
                )
                values (?, ?, ?, ?, ?, ?, ?, 'CONNECTED')
                """, UUID.randomUUID(), roomId, userId, connectionId, websocketSessionId, deviceId, clientSessionId);
    }

    public void touch(String connectionId) {
        jdbcTemplate.update("""
                update room_connection_sessions
                set last_seen_at = ?
                where connection_id = ? and status = 'CONNECTED'
                """, OffsetDateTime.now(), connectionId);
    }

    public void disconnect(String connectionId, String reason) {
        OffsetDateTime now = OffsetDateTime.now();
        jdbcTemplate.update("""
                update room_connection_sessions
                set status = 'DISCONNECTED', disconnected_at = ?, disconnect_reason = ?, last_seen_at = ?
                where connection_id = ? and status = 'CONNECTED'
                """, now, reason, now, connectionId);
    }

    public List<ConnectionSession> findByRoomId(UUID roomId) {
        return jdbcTemplate.query("""
                select id, room_id, user_id, connection_id, websocket_session_id, device_id, client_session_id,
                       status, connected_at, last_seen_at, disconnected_at, disconnect_reason
                from room_connection_sessions
                where room_id = ?
                order by connected_at desc, id desc
                """, rowMapper, roomId);
    }

    public List<ConnectionSession> findByUserId(UUID userId) {
        return jdbcTemplate.query("""
                select id, room_id, user_id, connection_id, websocket_session_id, device_id, client_session_id,
                       status, connected_at, last_seen_at, disconnected_at, disconnect_reason
                from room_connection_sessions
                where user_id = ?
                order by connected_at desc, id desc
                """, rowMapper, userId);
    }

    private ConnectionSession mapSession(ResultSet rs, int rowNum) throws SQLException {
        return new ConnectionSession(
                rs.getObject("id", UUID.class),
                rs.getObject("room_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("connection_id"),
                rs.getString("websocket_session_id"),
                rs.getString("device_id"),
                rs.getString("client_session_id"),
                rs.getString("status"),
                rs.getObject("connected_at", java.time.OffsetDateTime.class),
                rs.getObject("last_seen_at", java.time.OffsetDateTime.class),
                rs.getObject("disconnected_at", java.time.OffsetDateTime.class),
                rs.getString("disconnect_reason"));
    }
}
