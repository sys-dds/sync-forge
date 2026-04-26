package com.syncforge.api.backpressure.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.syncforge.api.backpressure.model.FlowStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ConnectionFlowControlRepository {
    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<FlowStatus> rowMapper = this::map;

    public ConnectionFlowControlRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void createActive(String connectionId, UUID roomId, UUID userId, String websocketSessionId, String nodeId, int maxQueuedMessages) {
        jdbcTemplate.update("""
                insert into websocket_connection_flow_controls (
                    connection_id, room_id, user_id, websocket_session_id, node_id, status, max_queued_messages
                )
                values (?, ?, ?, ?, ?, 'ACTIVE', ?)
                on conflict (connection_id) do update
                set room_id = excluded.room_id,
                    user_id = excluded.user_id,
                    websocket_session_id = excluded.websocket_session_id,
                    node_id = excluded.node_id,
                    status = 'ACTIVE',
                    queued_messages = 0,
                    max_queued_messages = excluded.max_queued_messages,
                    updated_at = now()
                """, connectionId, roomId, userId, websocketSessionId, nodeId, maxQueuedMessages);
    }

    public void updateQueued(String connectionId, int queuedMessages) {
        jdbcTemplate.update("""
                update websocket_connection_flow_controls
                set queued_messages = ?, updated_at = now()
                where connection_id = ?
                """, queuedMessages, connectionId);
    }

    public void markSendStarted(String connectionId) {
        jdbcTemplate.update("""
                update websocket_connection_flow_controls
                set last_send_started_at = now(), updated_at = now()
                where connection_id = ?
                """, connectionId);
    }

    public void markSendCompleted(String connectionId) {
        jdbcTemplate.update("""
                update websocket_connection_flow_controls
                set last_send_completed_at = now(), last_send_error = null, updated_at = now()
                where connection_id = ?
                """, connectionId);
    }

    public void markSlow(String connectionId, int queuedMessages) {
        jdbcTemplate.update("""
                update websocket_connection_flow_controls
                set status = 'SLOW', queued_messages = ?, updated_at = now()
                where connection_id = ? and status = 'ACTIVE'
                """, queuedMessages, connectionId);
    }

    public void markClosed(String connectionId) {
        jdbcTemplate.update("""
                update websocket_connection_flow_controls
                set status = 'CLOSED', queued_messages = 0, updated_at = now()
                where connection_id = ?
                """, connectionId);
    }

    public void markQuarantined(String connectionId) {
        jdbcTemplate.update("""
                update websocket_connection_flow_controls
                set status = 'QUARANTINED', updated_at = now()
                where connection_id = ? and status <> 'CLOSED'
                """, connectionId);
    }

    public void markActive(String connectionId) {
        jdbcTemplate.update("""
                update websocket_connection_flow_controls
                set status = 'ACTIVE', updated_at = now()
                where connection_id = ? and status = 'QUARANTINED'
                """, connectionId);
    }

    public void recordSendFailure(String connectionId, String error) {
        jdbcTemplate.update("""
                update websocket_connection_flow_controls
                set last_send_error = ?, status = 'CLOSED', queued_messages = 0, updated_at = now()
                where connection_id = ?
                """, error, connectionId);
    }

    public Optional<FlowStatus> find(String connectionId) {
        return jdbcTemplate.query("""
                select connection_id, room_id, user_id, websocket_session_id, node_id, status,
                       queued_messages, max_queued_messages, last_send_started_at, last_send_completed_at,
                       last_send_error, created_at, updated_at
                from websocket_connection_flow_controls
                where connection_id = ?
                """, rowMapper, connectionId).stream().findFirst();
    }

    public List<FlowStatus> listByRoom(UUID roomId) {
        return jdbcTemplate.query("""
                select connection_id, room_id, user_id, websocket_session_id, node_id, status,
                       queued_messages, max_queued_messages, last_send_started_at, last_send_completed_at,
                       last_send_error, created_at, updated_at
                from websocket_connection_flow_controls
                where room_id = ?
                order by created_at, connection_id
                """, rowMapper, roomId);
    }

    private FlowStatus map(ResultSet rs, int rowNum) throws SQLException {
        return new FlowStatus(
                rs.getString("connection_id"),
                rs.getObject("room_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("websocket_session_id"),
                rs.getString("node_id"),
                rs.getString("status"),
                rs.getInt("queued_messages"),
                rs.getInt("max_queued_messages"),
                rs.getObject("last_send_started_at", OffsetDateTime.class),
                rs.getObject("last_send_completed_at", OffsetDateTime.class),
                rs.getString("last_send_error"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }
}
