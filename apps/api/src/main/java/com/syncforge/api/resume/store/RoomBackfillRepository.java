package com.syncforge.api.resume.store;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RoomBackfillRepository {
    private final JdbcTemplate jdbcTemplate;

    public RoomBackfillRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void record(
            UUID roomId,
            UUID userId,
            String clientSessionId,
            long fromRoomSeq,
            long toRoomSeq,
            String outcome,
            int eventCount,
            String reason) {
        jdbcTemplate.update("""
                insert into room_backfill_requests (
                    id, room_id, user_id, client_session_id, from_room_seq, to_room_seq, outcome, event_count, reason
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), roomId, userId, clientSessionId, fromRoomSeq, toRoomSeq, outcome, eventCount, reason);
    }
}
