package com.syncforge.api.stream.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import com.syncforge.api.stream.model.RoomStreamOffset;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class RoomStreamOffsetRepository {
    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<RoomStreamOffset> rowMapper = this::map;

    public RoomStreamOffsetRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public RoomStreamOffset ensure(UUID roomId, String nodeId, String streamKey) {
        jdbcTemplate.update("""
                insert into room_stream_offsets (room_id, node_id, stream_key)
                values (?, ?, ?)
                on conflict (room_id, node_id) do update
                set stream_key = excluded.stream_key,
                    updated_at = now()
                """, roomId, nodeId, streamKey);
        return find(roomId, nodeId).orElseThrow();
    }

    public void update(UUID roomId, String nodeId, String streamKey, String lastStreamId, long lastRoomSeq) {
        jdbcTemplate.update("""
                insert into room_stream_offsets (room_id, node_id, stream_key, last_stream_id, last_room_seq)
                values (?, ?, ?, ?, ?)
                on conflict (room_id, node_id) do update
                set stream_key = excluded.stream_key,
                    last_stream_id = excluded.last_stream_id,
                    last_room_seq = greatest(room_stream_offsets.last_room_seq, excluded.last_room_seq),
                    updated_at = now()
                """, roomId, nodeId, streamKey, lastStreamId, lastRoomSeq);
    }

    public Optional<RoomStreamOffset> find(UUID roomId, String nodeId) {
        return jdbcTemplate.query("""
                select room_id, node_id, stream_key, last_stream_id, last_room_seq, updated_at
                from room_stream_offsets
                where room_id = ? and node_id = ?
                """, rowMapper, roomId, nodeId).stream().findFirst();
    }

    private RoomStreamOffset map(ResultSet rs, int rowNum) throws SQLException {
        return new RoomStreamOffset(
                rs.getObject("room_id", UUID.class),
                rs.getString("node_id"),
                rs.getString("stream_key"),
                rs.getString("last_stream_id"),
                rs.getLong("last_room_seq"),
                rs.getObject("updated_at", OffsetDateTime.class));
    }
}
