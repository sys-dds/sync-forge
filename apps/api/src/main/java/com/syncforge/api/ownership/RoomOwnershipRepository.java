package com.syncforge.api.ownership;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RoomOwnershipRepository {
    private final JdbcTemplate jdbcTemplate;

    public RoomOwnershipRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<RoomOwnershipLease> find(UUID roomId) {
        return jdbcTemplate.query("""
                select room_id, owner_node_id, fencing_token, lease_status, lease_expires_at,
                       acquired_at, renewed_at, released_at, last_takeover_reason, created_at, updated_at
                from room_ownership_leases
                where room_id = ?
                """, this::mapLease, roomId).stream().findFirst();
    }

    public Optional<RoomOwnershipLease> lock(UUID roomId) {
        return jdbcTemplate.query("""
                select room_id, owner_node_id, fencing_token, lease_status, lease_expires_at,
                       acquired_at, renewed_at, released_at, last_takeover_reason, created_at, updated_at
                from room_ownership_leases
                where room_id = ?
                for update
                """, this::mapLease, roomId).stream().findFirst();
    }

    public Optional<RoomOwnershipLease> insertFirst(UUID roomId, String nodeId, OffsetDateTime expiresAt) {
        return jdbcTemplate.query("""
                insert into room_ownership_leases (
                    room_id, owner_node_id, fencing_token, lease_status, lease_expires_at
                )
                values (?, ?, 1, 'ACTIVE', ?)
                on conflict (room_id) do nothing
                returning room_id, owner_node_id, fencing_token, lease_status, lease_expires_at,
                          acquired_at, renewed_at, released_at, last_takeover_reason, created_at, updated_at
                """, this::mapLease, roomId, nodeId, expiresAt).stream().findFirst();
    }

    public RoomOwnershipLease renew(UUID roomId, String nodeId, long fencingToken, OffsetDateTime expiresAt) {
        return jdbcTemplate.queryForObject("""
                update room_ownership_leases
                set lease_status = 'ACTIVE',
                    lease_expires_at = ?,
                    renewed_at = now(),
                    released_at = null,
                    updated_at = now()
                where room_id = ? and owner_node_id = ? and fencing_token = ?
                returning room_id, owner_node_id, fencing_token, lease_status, lease_expires_at,
                          acquired_at, renewed_at, released_at, last_takeover_reason, created_at, updated_at
                """, this::mapLease, expiresAt, roomId, nodeId, fencingToken);
    }

    public RoomOwnershipLease takeover(UUID roomId, String nodeId, long nextToken, OffsetDateTime expiresAt, String reason) {
        return jdbcTemplate.queryForObject("""
                update room_ownership_leases
                set owner_node_id = ?,
                    fencing_token = ?,
                    lease_status = 'ACTIVE',
                    lease_expires_at = ?,
                    acquired_at = now(),
                    renewed_at = null,
                    released_at = null,
                    last_takeover_reason = ?,
                    updated_at = now()
                where room_id = ?
                returning room_id, owner_node_id, fencing_token, lease_status, lease_expires_at,
                          acquired_at, renewed_at, released_at, last_takeover_reason, created_at, updated_at
                """, this::mapLease, nodeId, nextToken, expiresAt, reason, roomId);
    }

    public Optional<RoomOwnershipLease> release(UUID roomId, String nodeId, long fencingToken, String reason) {
        return jdbcTemplate.query("""
                update room_ownership_leases
                set lease_status = 'RELEASED',
                    released_at = now(),
                    last_takeover_reason = ?,
                    updated_at = now()
                where room_id = ? and owner_node_id = ? and fencing_token = ? and lease_status = 'ACTIVE'
                returning room_id, owner_node_id, fencing_token, lease_status, lease_expires_at,
                          acquired_at, renewed_at, released_at, last_takeover_reason, created_at, updated_at
                """, this::mapLease, reason, roomId, nodeId, fencingToken).stream().findFirst();
    }

    public int markExpired(OffsetDateTime now) {
        return jdbcTemplate.update("""
                update room_ownership_leases
                set lease_status = 'EXPIRED',
                    updated_at = now()
                where lease_status = 'ACTIVE' and lease_expires_at <= ?
                """, now);
    }

    public void recordEvent(
            UUID roomId,
            String nodeId,
            Long fencingToken,
            String eventType,
            String reason,
            String previousOwnerNodeId,
            Long previousFencingToken) {
        jdbcTemplate.update("""
                insert into room_ownership_events (
                    id, room_id, node_id, fencing_token, event_type, reason,
                    previous_owner_node_id, previous_fencing_token
                )
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), roomId, nodeId, fencingToken, eventType, reason,
                previousOwnerNodeId, previousFencingToken);
    }

    public Optional<RoomOwnershipEvent> latestEvent(UUID roomId) {
        return jdbcTemplate.query("""
                select id, room_id, node_id, fencing_token, event_type, reason,
                       previous_owner_node_id, previous_fencing_token, created_at
                from room_ownership_events
                where room_id = ?
                order by created_at desc, id desc
                limit 1
                """, this::mapEvent, roomId).stream().findFirst();
    }

    public List<RoomOwnershipEvent> events(UUID roomId) {
        return jdbcTemplate.query("""
                select id, room_id, node_id, fencing_token, event_type, reason,
                       previous_owner_node_id, previous_fencing_token, created_at
                from room_ownership_events
                where room_id = ?
                order by created_at, id
                """, this::mapEvent, roomId);
    }

    private RoomOwnershipLease mapLease(ResultSet rs, int rowNum) throws SQLException {
        return new RoomOwnershipLease(
                rs.getObject("room_id", UUID.class),
                rs.getString("owner_node_id"),
                rs.getLong("fencing_token"),
                RoomOwnershipStatus.valueOf(rs.getString("lease_status")),
                rs.getObject("lease_expires_at", OffsetDateTime.class),
                rs.getObject("acquired_at", OffsetDateTime.class),
                rs.getObject("renewed_at", OffsetDateTime.class),
                rs.getObject("released_at", OffsetDateTime.class),
                rs.getString("last_takeover_reason"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private RoomOwnershipEvent mapEvent(ResultSet rs, int rowNum) throws SQLException {
        return new RoomOwnershipEvent(
                rs.getObject("id", UUID.class),
                rs.getObject("room_id", UUID.class),
                rs.getString("node_id"),
                rs.getObject("fencing_token", Long.class),
                rs.getString("event_type"),
                rs.getString("reason"),
                rs.getString("previous_owner_node_id"),
                rs.getObject("previous_fencing_token", Long.class),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
