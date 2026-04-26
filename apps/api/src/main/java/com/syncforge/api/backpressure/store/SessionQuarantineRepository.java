package com.syncforge.api.backpressure.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.syncforge.api.backpressure.model.SessionQuarantine;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class SessionQuarantineRepository {
    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<SessionQuarantine> rowMapper = this::map;

    public SessionQuarantineRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public SessionQuarantine create(
            UUID roomId,
            UUID userId,
            String connectionId,
            String clientSessionId,
            String nodeId,
            String reason,
            OffsetDateTime expiresAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into websocket_session_quarantines (
                    id, room_id, user_id, connection_id, client_session_id, node_id, reason, expires_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, roomId, userId, connectionId, clientSessionId, nodeId, reason, expiresAt);
        return findById(id).orElseThrow();
    }

    public Optional<SessionQuarantine> findActive(String connectionId, OffsetDateTime now) {
        return jdbcTemplate.query("""
                select id, room_id, user_id, connection_id, client_session_id, node_id, reason, started_at, expires_at, released_at
                from websocket_session_quarantines
                where connection_id = ?
                  and released_at is null
                  and expires_at > ?
                order by started_at desc
                limit 1
                """, rowMapper, connectionId, now).stream().findFirst();
    }

    public List<SessionQuarantine> listByRoom(UUID roomId) {
        return jdbcTemplate.query("""
                select id, room_id, user_id, connection_id, client_session_id, node_id, reason, started_at, expires_at, released_at
                from websocket_session_quarantines
                where room_id = ?
                order by started_at, id
                """, rowMapper, roomId);
    }

    public List<String> releaseExpired(OffsetDateTime now) {
        List<String> connectionIds = jdbcTemplate.queryForList("""
                select distinct connection_id
                from websocket_session_quarantines
                where released_at is null
                  and expires_at <= ?
                """, String.class, now);
        jdbcTemplate.update("""
                update websocket_session_quarantines
                set released_at = ?
                where released_at is null
                  and expires_at <= ?
                """, now, now);
        return connectionIds;
    }

    private Optional<SessionQuarantine> findById(UUID id) {
        return jdbcTemplate.query("""
                select id, room_id, user_id, connection_id, client_session_id, node_id, reason, started_at, expires_at, released_at
                from websocket_session_quarantines
                where id = ?
                """, rowMapper, id).stream().findFirst();
    }

    private SessionQuarantine map(ResultSet rs, int rowNum) throws SQLException {
        return new SessionQuarantine(
                rs.getObject("id", UUID.class),
                rs.getObject("room_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("connection_id"),
                rs.getString("client_session_id"),
                rs.getString("node_id"),
                rs.getString("reason"),
                rs.getObject("started_at", OffsetDateTime.class),
                rs.getObject("expires_at", OffsetDateTime.class),
                rs.getObject("released_at", OffsetDateTime.class));
    }
}
