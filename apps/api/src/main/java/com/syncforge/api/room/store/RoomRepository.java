package com.syncforge.api.room.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import com.syncforge.api.room.model.Room;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class RoomRepository {
    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<Room> rowMapper = this::mapRoom;

    public RoomRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Room create(UUID workspaceId, UUID documentId, String roomKey, String roomType) {
        UUID id = UUID.randomUUID();
        return jdbcTemplate.queryForObject("""
                insert into rooms (id, workspace_id, document_id, room_key, room_type, status)
                values (?, ?, ?, ?, ?, 'OPEN')
                returning id, workspace_id, document_id, room_key, room_type, status, created_at, updated_at
                """, rowMapper, id, workspaceId, documentId, roomKey, roomType);
    }

    public Optional<Room> findById(UUID id) {
        return jdbcTemplate.query("""
                select id, workspace_id, document_id, room_key, room_type, status, created_at, updated_at
                from rooms
                where id = ?
                """, rowMapper, id).stream().findFirst();
    }

    public boolean existsById(UUID id) {
        Boolean exists = jdbcTemplate.queryForObject("select exists(select 1 from rooms where id = ?)", Boolean.class, id);
        return Boolean.TRUE.equals(exists);
    }

    private Room mapRoom(ResultSet rs, int rowNum) throws SQLException {
        return new Room(
                rs.getObject("id", UUID.class),
                rs.getObject("workspace_id", UUID.class),
                rs.getObject("document_id", UUID.class),
                rs.getString("room_key"),
                rs.getString("room_type"),
                rs.getString("status"),
                rs.getObject("created_at", java.time.OffsetDateTime.class),
                rs.getObject("updated_at", java.time.OffsetDateTime.class));
    }
}
