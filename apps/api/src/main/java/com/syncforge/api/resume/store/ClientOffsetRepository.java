package com.syncforge.api.resume.store;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ClientOffsetRepository {
    private final JdbcTemplate jdbcTemplate;

    public ClientOffsetRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void upsert(UUID roomId, UUID userId, String clientSessionId, long lastSeenRoomSeq) {
        jdbcTemplate.update("""
                insert into room_client_offsets (room_id, user_id, client_session_id, last_seen_room_seq, updated_at)
                values (?, ?, ?, ?, ?)
                on conflict (room_id, user_id, client_session_id) do update
                set last_seen_room_seq = excluded.last_seen_room_seq,
                    updated_at = excluded.updated_at
                """, roomId, userId, clientSessionId, lastSeenRoomSeq, OffsetDateTime.now());
    }

    public Optional<Long> find(UUID roomId, UUID userId, String clientSessionId) {
        Long value = jdbcTemplate.query("""
                select last_seen_room_seq
                from room_client_offsets
                where room_id = ? and user_id = ? and client_session_id = ?
                """, rs -> rs.next() ? rs.getLong("last_seen_room_seq") : null, roomId, userId, clientSessionId);
        return Optional.ofNullable(value);
    }
}
