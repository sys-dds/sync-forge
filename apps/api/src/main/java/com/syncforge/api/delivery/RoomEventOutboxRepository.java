package com.syncforge.api.delivery;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class RoomEventOutboxRepository {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RowMapper<RoomEventOutboxRecord> rowMapper = this::map;

    public RoomEventOutboxRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public RoomEventOutboxRecord insertPendingOperationEvent(
            UUID id,
            UUID roomId,
            long roomSeq,
            long revision,
            String operationId,
            String logicalEventKey,
            Map<String, Object> payload) {
        return insertPendingOperationEvent(id, roomId, roomSeq, revision, operationId, logicalEventKey, payload, null, null);
    }

    public RoomEventOutboxRecord insertPendingOperationEvent(
            UUID id,
            UUID roomId,
            long roomSeq,
            long revision,
            String operationId,
            String logicalEventKey,
            Map<String, Object> payload,
            String ownerNodeId,
            Long fencingToken) {
        jdbcTemplate.update("""
                insert into room_event_outbox (
                    id, room_id, room_seq, revision, operation_id, event_type,
                    logical_event_key, payload_json, status, owner_node_id, fencing_token
                )
                values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                on conflict (logical_event_key) do nothing
                """, id, roomId, roomSeq, revision, operationId, RoomEventPayloadFactory.OPERATION_APPLIED,
                logicalEventKey, writePayload(payload), RoomEventOutboxStatus.PENDING.name(), ownerNodeId, fencingToken);
        return findByRoomSeq(roomId, roomSeq).orElseThrow();
    }

    public List<RoomEventOutboxRecord> findDueForDispatch(int limit, String nodeId, Duration lockTtl) {
        return jdbcTemplate.query("""
                with due as (
                    select id
                    from room_event_outbox
                    where status in ('PENDING', 'RETRY')
                      and next_attempt_at <= now()
                      and (locked_until is null or locked_until < now())
                    order by created_at, room_seq
                    limit ?
                    for update skip locked
                )
                update room_event_outbox outbox
                set status = 'PUBLISHING',
                    locked_by = ?,
                    locked_until = now() + (? * interval '1 millisecond'),
                    updated_at = now()
                from due
                where outbox.id = due.id
                returning outbox.*
                """, rowMapper, limit, nodeId, lockTtl.toMillis());
    }

    public Optional<RoomEventOutboxRecord> markPublished(UUID id, String streamKey, String streamId) {
        return jdbcTemplate.query("""
                update room_event_outbox
                set status = 'PUBLISHED',
                    locked_by = null,
                    locked_until = null,
                    last_error = null,
                    published_stream_key = ?,
                    published_stream_id = ?,
                    published_at = now(),
                    updated_at = now()
                where id = ?
                returning *
                """, rowMapper, streamKey, streamId, id).stream().findFirst();
    }

    public Optional<RoomEventOutboxRecord> markRetry(UUID id, String error, OffsetDateTime nextAttemptAt) {
        return jdbcTemplate.query("""
                update room_event_outbox
                set attempt_count = attempt_count + 1,
                    status = case when attempt_count + 1 >= max_attempts then 'PARKED' else 'RETRY' end,
                    next_attempt_at = ?,
                    locked_by = null,
                    locked_until = null,
                    last_error = ?,
                    parked_at = case when attempt_count + 1 >= max_attempts then now() else parked_at end,
                    updated_at = now()
                where id = ?
                returning *
                """, rowMapper, nextAttemptAt, truncateError(error), id).stream().findFirst();
    }

    public Optional<RoomEventOutboxRecord> markParked(UUID id, String error) {
        return jdbcTemplate.query("""
                update room_event_outbox
                set status = 'PARKED',
                    locked_by = null,
                    locked_until = null,
                    last_error = ?,
                    parked_at = now(),
                    updated_at = now()
                where id = ?
                returning *
                """, rowMapper, truncateError(error), id).stream().findFirst();
    }

    public int releaseExpiredLocks() {
        return jdbcTemplate.update("""
                update room_event_outbox
                set status = 'RETRY',
                    locked_by = null,
                    locked_until = null,
                    updated_at = now()
                where status = 'PUBLISHING'
                  and locked_until < now()
                """);
    }

    public Optional<RoomEventOutboxRecord> findByRoomSeq(UUID roomId, long roomSeq) {
        return jdbcTemplate.query("""
                select *
                from room_event_outbox
                where room_id = ? and room_seq = ?
                """, rowMapper, roomId, roomSeq).stream().findFirst();
    }

    public Optional<RoomEventOutboxRecord> findByLogicalEventKey(String logicalEventKey) {
        return jdbcTemplate.query("""
                select *
                from room_event_outbox
                where logical_event_key = ?
                """, rowMapper, logicalEventKey).stream().findFirst();
    }

    public long countByStatus(RoomEventOutboxStatus status) {
        Long count = jdbcTemplate.queryForObject("""
                select count(*)
                from room_event_outbox
                where status = ?
                """, Long.class, status.name());
        return count == null ? 0L : count;
    }

    private RoomEventOutboxRecord map(ResultSet rs, int rowNum) throws SQLException {
        return new RoomEventOutboxRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("room_id", UUID.class),
                rs.getLong("room_seq"),
                rs.getLong("revision"),
                rs.getString("operation_id"),
                rs.getString("event_type"),
                rs.getString("logical_event_key"),
                readPayload(rs.getString("payload_json")),
                RoomEventOutboxStatus.valueOf(rs.getString("status")),
                rs.getInt("attempt_count"),
                rs.getInt("max_attempts"),
                rs.getObject("next_attempt_at", OffsetDateTime.class),
                rs.getString("locked_by"),
                rs.getObject("locked_until", OffsetDateTime.class),
                rs.getString("last_error"),
                rs.getString("published_stream_key"),
                rs.getString("published_stream_id"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class),
                rs.getObject("published_at", OffsetDateTime.class),
                rs.getObject("parked_at", OffsetDateTime.class),
                rs.getString("owner_node_id"),
                rs.getObject("fencing_token", Long.class));
    }

    private String writePayload(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to serialize room event outbox payload", exception);
        }
    }

    private Map<String, Object> readPayload(String payload) {
        try {
            return objectMapper.readValue(payload, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to deserialize room event outbox payload", exception);
        }
    }

    private String truncateError(String error) {
        if (error == null || error.length() <= 1000) {
            return error;
        }
        return error.substring(0, 1000);
    }
}
