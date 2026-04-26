package com.syncforge.api.resume.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.syncforge.api.resume.model.ResumeToken;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ResumeTokenRepository {
    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<ResumeToken> rowMapper = this::mapToken;

    public ResumeTokenRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public ResumeToken create(
            UUID roomId,
            UUID userId,
            String connectionId,
            String clientSessionId,
            String tokenHash,
            OffsetDateTime issuedAt,
            OffsetDateTime expiresAt,
            long lastSeenRoomSeq) {
        return jdbcTemplate.queryForObject("""
                insert into room_resume_tokens (
                    id, room_id, user_id, connection_id, client_session_id, token_hash, issued_at, expires_at, last_seen_room_seq
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                returning id, room_id, user_id, connection_id, client_session_id, token_hash,
                          issued_at, expires_at, revoked_at, last_seen_room_seq
                """, rowMapper, UUID.randomUUID(), roomId, userId, connectionId, clientSessionId, tokenHash,
                issuedAt, expiresAt, lastSeenRoomSeq);
    }

    public Optional<ResumeToken> findByHash(String tokenHash) {
        List<ResumeToken> tokens = jdbcTemplate.query("""
                select id, room_id, user_id, connection_id, client_session_id, token_hash,
                       issued_at, expires_at, revoked_at, last_seen_room_seq
                from room_resume_tokens
                where token_hash = ?
                """, rowMapper, tokenHash);
        return tokens.stream().findFirst();
    }

    public void updateLastSeen(String tokenHash, long lastSeenRoomSeq) {
        jdbcTemplate.update("""
                update room_resume_tokens
                set last_seen_room_seq = ?
                where token_hash = ?
                """, lastSeenRoomSeq, tokenHash);
    }

    private ResumeToken mapToken(ResultSet rs, int rowNum) throws SQLException {
        return new ResumeToken(
                rs.getObject("id", UUID.class),
                rs.getObject("room_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("connection_id"),
                rs.getString("client_session_id"),
                rs.getString("token_hash"),
                rs.getObject("issued_at", OffsetDateTime.class),
                rs.getObject("expires_at", OffsetDateTime.class),
                rs.getObject("revoked_at", OffsetDateTime.class),
                rs.getLong("last_seen_room_seq"));
    }
}
