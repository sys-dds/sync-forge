package com.syncforge.api.node;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class CrossNodePresenceRepository {
    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<CrossNodePresenceState> rowMapper = this::map;

    public CrossNodePresenceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void updateLocal(UUID roomId, UUID userId, String nodeId, OffsetDateTime now) {
        Integer activeCount = jdbcTemplate.queryForObject("""
                select count(*)
                from room_presence_connections
                where room_id = ? and user_id = ? and status = 'PRESENT' and expires_at > ?
                """, Integer.class, roomId, userId, now);
        OffsetDateTime lastSeen = jdbcTemplate.query("""
                select last_seen_at
                from room_presence_connections
                where room_id = ? and user_id = ?
                order by last_seen_at desc
                limit 1
                """, rs -> rs.next() ? rs.getObject("last_seen_at", OffsetDateTime.class) : now, roomId, userId);
        int count = activeCount == null ? 0 : activeCount;
        jdbcTemplate.update("""
                insert into cross_node_presence_states (
                    room_id, user_id, node_id, active_connection_count, last_seen_at, status
                )
                values (?, ?, ?, ?, ?, ?)
                on conflict (room_id, user_id, node_id) do update
                set active_connection_count = excluded.active_connection_count,
                    last_seen_at = excluded.last_seen_at,
                    status = excluded.status
                """, roomId, userId, nodeId, count, lastSeen, count > 0 ? "ONLINE" : "OFFLINE");
    }

    public int markStale(OffsetDateTime staleBefore) {
        return jdbcTemplate.update("""
                update cross_node_presence_states
                set status = 'STALE'
                where status = 'ONLINE' and last_seen_at <= ?
                """, staleBefore);
    }

    public List<CrossNodePresenceState> listByRoom(UUID roomId) {
        return jdbcTemplate.query("""
                select room_id, user_id, node_id, active_connection_count, last_seen_at, status
                from cross_node_presence_states
                where room_id = ?
                order by user_id, node_id
                """, rowMapper, roomId);
    }

    private CrossNodePresenceState map(ResultSet rs, int rowNum) throws SQLException {
        return new CrossNodePresenceState(
                rs.getObject("room_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("node_id"),
                rs.getInt("active_connection_count"),
                rs.getObject("last_seen_at", OffsetDateTime.class),
                rs.getString("status"));
    }
}
