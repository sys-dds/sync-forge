package com.syncforge.api.runtime;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PoisonOperationRepository {
    private final JdbcTemplate jdbcTemplate;

    public PoisonOperationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public PoisonOperationRecord quarantine(UUID roomId, String operationId, Long roomSeq, String reason) {
        return jdbcTemplate.queryForObject("""
                insert into room_poison_operations (id, room_id, operation_id, room_seq, reason, status)
                values (?, ?, ?, ?, ?, 'QUARANTINED')
                on conflict (room_id, operation_id) do update
                set failure_count = room_poison_operations.failure_count + 1,
                    reason = excluded.reason,
                    room_seq = coalesce(excluded.room_seq, room_poison_operations.room_seq),
                    status = 'QUARANTINED',
                    last_seen_at = now()
                returning id, room_id, operation_id, room_seq, reason, failure_count,
                          first_seen_at, last_seen_at, status
                """, this::map, UUID.randomUUID(), roomId, operationId, roomSeq, reason);
    }

    public List<PoisonOperationRecord> listQuarantined(UUID roomId) {
        return jdbcTemplate.query("""
                select id, room_id, operation_id, room_seq, reason, failure_count,
                       first_seen_at, last_seen_at, status
                from room_poison_operations
                where room_id = ? and status = 'QUARANTINED'
                order by last_seen_at desc, operation_id
                """, this::map, roomId);
    }

    public long countQuarantined(UUID roomId) {
        Long count = jdbcTemplate.queryForObject("""
                select count(*)
                from room_poison_operations
                where room_id = ? and status = 'QUARANTINED'
                """, Long.class, roomId);
        return count == null ? 0 : count;
    }

    public void clear(UUID roomId) {
        jdbcTemplate.update("""
                update room_poison_operations
                set status = 'CLEARED',
                    last_seen_at = now()
                where room_id = ? and status = 'QUARANTINED'
                """, roomId);
    }

    private PoisonOperationRecord map(ResultSet rs, int rowNum) throws SQLException {
        return new PoisonOperationRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("room_id", UUID.class),
                rs.getString("operation_id"),
                rs.getObject("room_seq", Long.class),
                rs.getString("reason"),
                rs.getInt("failure_count"),
                rs.getObject("first_seen_at", OffsetDateTime.class),
                rs.getObject("last_seen_at", OffsetDateTime.class),
                rs.getString("status"));
    }
}
