package com.syncforge.api.ratelimit.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.syncforge.api.ratelimit.model.RateLimitDecision;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class OperationRateLimitRepository {
    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<RateLimitDecision> rowMapper = this::map;

    public OperationRateLimitRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void record(RateLimitDecision decision) {
        jdbcTemplate.update("""
                insert into room_rate_limit_events (
                    id, room_id, user_id, connection_id, client_session_id, operation_id,
                    limit_key, limit_value, observed_value, window_seconds, decision, reason
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), decision.roomId(), decision.userId(), decision.connectionId(),
                decision.clientSessionId(), decision.operationId(), decision.limitKey(), decision.limitValue(),
                decision.observedValue(), decision.windowSeconds(), decision.decision(), decision.reason());
    }

    public List<RateLimitDecision> listByRoom(UUID roomId) {
        return jdbcTemplate.query("""
                select room_id, user_id, connection_id, client_session_id, operation_id,
                       limit_key, limit_value, observed_value, window_seconds, decision, reason, created_at
                from room_rate_limit_events
                where room_id = ?
                order by created_at, id
                """, rowMapper, roomId);
    }

    private RateLimitDecision map(ResultSet rs, int rowNum) throws SQLException {
        String decision = rs.getString("decision");
        int windowSeconds = rs.getInt("window_seconds");
        return new RateLimitDecision(
                "ALLOWED".equals(decision),
                rs.getObject("room_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("connection_id"),
                rs.getString("client_session_id"),
                rs.getString("operation_id"),
                rs.getString("limit_key"),
                rs.getInt("limit_value"),
                rs.getInt("observed_value"),
                windowSeconds,
                decision,
                rs.getString("reason"),
                Math.max(1, windowSeconds) * 1000L,
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
