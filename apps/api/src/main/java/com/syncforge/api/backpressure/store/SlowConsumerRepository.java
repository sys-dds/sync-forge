package com.syncforge.api.backpressure.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.syncforge.api.backpressure.model.SlowConsumerEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class SlowConsumerRepository {
    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<SlowConsumerEvent> rowMapper = this::map;

    public SlowConsumerRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public SlowConsumerEvent record(
            UUID roomId,
            UUID userId,
            String connectionId,
            String nodeId,
            int queuedMessages,
            int threshold,
            String decision,
            String reason) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into websocket_slow_consumer_events (
                    id, room_id, user_id, connection_id, node_id, queued_messages, threshold, decision, reason
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, roomId, userId, connectionId, nodeId, queuedMessages, threshold, decision, reason);
        return jdbcTemplate.queryForObject("""
                select id, room_id, user_id, connection_id, node_id, queued_messages, threshold, decision, reason, created_at
                from websocket_slow_consumer_events
                where id = ?
                """, rowMapper, id);
    }

    public List<SlowConsumerEvent> listByRoom(UUID roomId) {
        return jdbcTemplate.query("""
                select id, room_id, user_id, connection_id, node_id, queued_messages, threshold, decision, reason, created_at
                from websocket_slow_consumer_events
                where room_id = ?
                order by created_at, id
                """, rowMapper, roomId);
    }

    private SlowConsumerEvent map(ResultSet rs, int rowNum) throws SQLException {
        return new SlowConsumerEvent(
                rs.getObject("id", UUID.class),
                rs.getObject("room_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("connection_id"),
                rs.getString("node_id"),
                rs.getInt("queued_messages"),
                rs.getInt("threshold"),
                rs.getString("decision"),
                rs.getString("reason"),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
