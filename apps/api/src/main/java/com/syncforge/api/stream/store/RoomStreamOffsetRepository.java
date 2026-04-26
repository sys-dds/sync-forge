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
                    status = 'NORMAL',
                    expected_room_seq = null,
                    observed_room_seq = null,
                    last_gap_at = null,
                    last_error = null,
                    updated_at = now()
                """, roomId, nodeId, streamKey, lastStreamId, lastRoomSeq);
    }

    public void markGap(UUID roomId, String nodeId, String streamKey, long expectedRoomSeq, long observedRoomSeq, String error) {
        jdbcTemplate.update("""
                insert into room_stream_offsets (
                    room_id, node_id, stream_key, status, expected_room_seq, observed_room_seq, last_gap_at, last_error
                )
                values (?, ?, ?, 'GAP_DETECTED', ?, ?, now(), ?)
                on conflict (room_id, node_id) do update
                set stream_key = excluded.stream_key,
                    status = 'GAP_DETECTED',
                    expected_room_seq = excluded.expected_room_seq,
                    observed_room_seq = excluded.observed_room_seq,
                    last_gap_at = excluded.last_gap_at,
                    last_error = excluded.last_error,
                    updated_at = now()
                """, roomId, nodeId, streamKey, expectedRoomSeq, observedRoomSeq, error);
    }

    public Optional<RoomStreamOffset> find(UUID roomId, String nodeId) {
        return jdbcTemplate.query("""
                select room_id, node_id, stream_key, last_stream_id, last_room_seq, status,
                       expected_room_seq, observed_room_seq, last_gap_at, last_error, updated_at
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
                rs.getString("status"),
                nullableLong(rs, "expected_room_seq"),
                nullableLong(rs, "observed_room_seq"),
                rs.getObject("last_gap_at", OffsetDateTime.class),
                rs.getString("last_error"),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
