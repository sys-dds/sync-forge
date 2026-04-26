package com.syncforge.api.operation.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.syncforge.api.operation.model.RoomSequence;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RoomSequenceRepository {
    private final JdbcTemplate jdbcTemplate;

    public RoomSequenceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public RoomSequence lockForUpdate(UUID roomId) {
        jdbcTemplate.update("""
                insert into room_sequence_counters (room_id)
                values (?)
                on conflict (room_id) do nothing
                """, roomId);
        return jdbcTemplate.queryForObject("""
                select room_id, current_room_seq, current_revision, updated_at
                from room_sequence_counters
                where room_id = ?
                for update
                """, this::mapSequence, roomId);
    }

    public RoomSequence advance(UUID roomId, long roomSeq, long revision) {
        return jdbcTemplate.queryForObject("""
                update room_sequence_counters
                set current_room_seq = ?, current_revision = ?, updated_at = ?
                where room_id = ?
                returning room_id, current_room_seq, current_revision, updated_at
                """, this::mapSequence, roomSeq, revision, OffsetDateTime.now(), roomId);
    }

    public RoomSequence find(UUID roomId) {
        return jdbcTemplate.query("""
                select room_id, current_room_seq, current_revision, updated_at
                from room_sequence_counters
                where room_id = ?
                """, rs -> rs.next() ? mapSequence(rs, 0) : new RoomSequence(roomId, 0, 0, null), roomId);
    }

    private RoomSequence mapSequence(ResultSet rs, int rowNum) throws SQLException {
        return new RoomSequence(
                rs.getObject("room_id", UUID.class),
                rs.getLong("current_room_seq"),
                rs.getLong("current_revision"),
                rs.getObject("updated_at", OffsetDateTime.class));
    }
}
