package com.syncforge.api.stream.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.syncforge.api.stream.model.NodeRoomSubscription;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class NodeRoomSubscriptionRepository {
    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<NodeRoomSubscription> rowMapper = this::map;

    public NodeRoomSubscriptionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void increment(String nodeId, UUID roomId) {
        jdbcTemplate.update("""
                insert into node_room_subscriptions (node_id, room_id, local_connection_count)
                values (?, ?, 1)
                on conflict (node_id, room_id) do update
                set local_connection_count = node_room_subscriptions.local_connection_count + 1
                """, nodeId, roomId);
    }

    public void decrement(String nodeId, UUID roomId) {
        jdbcTemplate.update("""
                update node_room_subscriptions
                set local_connection_count = greatest(0, local_connection_count - 1)
                where node_id = ? and room_id = ?
                """, nodeId, roomId);
    }

    public void markEvent(String nodeId, UUID roomId) {
        jdbcTemplate.update("""
                update node_room_subscriptions
                set last_event_at = now()
                where node_id = ? and room_id = ?
                """, nodeId, roomId);
    }

    public List<NodeRoomSubscription> activeSubscriptions(String nodeId) {
        return jdbcTemplate.query("""
                select node_id, room_id, local_connection_count, subscribed_at, last_event_at
                from node_room_subscriptions
                where node_id = ? and local_connection_count > 0
                order by subscribed_at, room_id
                """, rowMapper, nodeId);
    }

    private NodeRoomSubscription map(ResultSet rs, int rowNum) throws SQLException {
        return new NodeRoomSubscription(
                rs.getString("node_id"),
                rs.getObject("room_id", UUID.class),
                rs.getInt("local_connection_count"),
                rs.getObject("subscribed_at", OffsetDateTime.class),
                rs.getObject("last_event_at", OffsetDateTime.class));
    }
}
