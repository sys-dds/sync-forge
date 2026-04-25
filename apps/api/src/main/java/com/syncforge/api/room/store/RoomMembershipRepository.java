package com.syncforge.api.room.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.syncforge.api.room.model.RoomMembership;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class RoomMembershipRepository {
    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<RoomMembership> rowMapper = this::mapMembership;

    public RoomMembershipRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public RoomMembership upsert(UUID roomId, UUID userId, String role) {
        UUID id = UUID.randomUUID();
        return jdbcTemplate.queryForObject("""
                insert into room_memberships (id, room_id, user_id, role, status)
                values (?, ?, ?, ?, 'ACTIVE')
                on conflict (room_id, user_id)
                do update set role = excluded.role, status = 'ACTIVE', updated_at = now()
                returning id, room_id, user_id, role, status, created_at, updated_at
                """, rowMapper, id, roomId, userId, role);
    }

    public List<RoomMembership> findByRoomId(UUID roomId) {
        return jdbcTemplate.query("""
                select id, room_id, user_id, role, status, created_at, updated_at
                from room_memberships
                where room_id = ?
                order by created_at, id
                """, rowMapper, roomId);
    }

    public Optional<RoomMembership> findByRoomAndUser(UUID roomId, UUID userId) {
        return jdbcTemplate.query("""
                select id, room_id, user_id, role, status, created_at, updated_at
                from room_memberships
                where room_id = ? and user_id = ?
                """, rowMapper, roomId, userId).stream().findFirst();
    }

    private RoomMembership mapMembership(ResultSet rs, int rowNum) throws SQLException {
        return new RoomMembership(
                rs.getObject("id", UUID.class),
                rs.getObject("room_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("role"),
                rs.getString("status"),
                rs.getObject("created_at", java.time.OffsetDateTime.class),
                rs.getObject("updated_at", java.time.OffsetDateTime.class));
    }
}
