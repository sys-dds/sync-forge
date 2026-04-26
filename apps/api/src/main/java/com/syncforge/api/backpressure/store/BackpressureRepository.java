package com.syncforge.api.backpressure.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import com.syncforge.api.backpressure.model.RoomBackpressureState;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class BackpressureRepository {
    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<RoomBackpressureState> rowMapper = this::map;

    public BackpressureRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public RoomBackpressureState ensure(UUID roomId, int maxPendingEvents) {
        jdbcTemplate.update("""
                insert into room_backpressure_states (room_id, status, pending_events, max_pending_events)
                values (?, 'NORMAL', 0, ?)
                on conflict (room_id) do update
                set max_pending_events = excluded.max_pending_events,
                    updated_at = now()
                """, roomId, maxPendingEvents);
        return find(roomId).orElseThrow();
    }

    public RoomBackpressureState increment(UUID roomId, int warningPendingEvents, int maxPendingEvents) {
        jdbcTemplate.update("""
                insert into room_backpressure_states (
                    room_id, status, pending_events, max_pending_events, last_triggered_at, reason
                )
                values (?, case when 1 >= ? then 'REJECTING' when 1 >= ? then 'WARNING' else 'NORMAL' end, 1, ?, 
                        case when 1 >= ? then now() else null end,
                        case when 1 >= ? then 'room pending event threshold reached' else null end)
                on conflict (room_id) do update
                set pending_events = room_backpressure_states.pending_events + 1,
                    max_pending_events = excluded.max_pending_events,
                    status = case
                        when room_backpressure_states.pending_events + 1 >= ? then 'REJECTING'
                        when room_backpressure_states.pending_events + 1 >= ? then 'WARNING'
                        else 'NORMAL'
                    end,
                    last_triggered_at = case
                        when room_backpressure_states.pending_events + 1 >= ? then now()
                        else room_backpressure_states.last_triggered_at
                    end,
                    reason = case
                        when room_backpressure_states.pending_events + 1 >= ? then 'room pending event threshold reached'
                        else room_backpressure_states.reason
                    end,
                    updated_at = now()
                """, roomId, maxPendingEvents, warningPendingEvents, maxPendingEvents, warningPendingEvents,
                warningPendingEvents, maxPendingEvents, warningPendingEvents, warningPendingEvents, warningPendingEvents);
        return find(roomId).orElseThrow();
    }

    public RoomBackpressureState decrement(UUID roomId, int warningPendingEvents, int maxPendingEvents) {
        jdbcTemplate.update("""
                update room_backpressure_states
                set pending_events = greatest(0, pending_events - 1),
                    max_pending_events = ?,
                    status = case
                        when greatest(0, pending_events - 1) >= ? then 'REJECTING'
                        when greatest(0, pending_events - 1) >= ? then 'WARNING'
                        else 'NORMAL'
                    end,
                    last_cleared_at = case when greatest(0, pending_events - 1) < ? then now() else last_cleared_at end,
                    updated_at = now()
                where room_id = ?
                """, maxPendingEvents, maxPendingEvents, warningPendingEvents, warningPendingEvents, roomId);
        return find(roomId).orElseGet(() -> ensure(roomId, maxPendingEvents));
    }

    public Optional<RoomBackpressureState> find(UUID roomId) {
        return jdbcTemplate.query("""
                select room_id, status, pending_events, max_pending_events, last_triggered_at,
                       last_cleared_at, reason, updated_at
                from room_backpressure_states
                where room_id = ?
                """, rowMapper, roomId).stream().findFirst();
    }

    private RoomBackpressureState map(ResultSet rs, int rowNum) throws SQLException {
        return new RoomBackpressureState(
                rs.getObject("room_id", UUID.class),
                rs.getString("status"),
                rs.getInt("pending_events"),
                rs.getInt("max_pending_events"),
                rs.getObject("last_triggered_at", OffsetDateTime.class),
                rs.getObject("last_cleared_at", OffsetDateTime.class),
                rs.getString("reason"),
                rs.getObject("updated_at", OffsetDateTime.class));
    }
}
