package com.syncforge.api.operation.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncforge.api.operation.model.OperationRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class OperationRepository {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RowMapper<OperationRecord> rowMapper = this::mapOperation;

    public OperationRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public OperationRecord insert(
            UUID roomId,
            UUID userId,
            String connectionId,
            String operationId,
            String clientSessionId,
            long clientSeq,
            long baseRevision,
            long roomSeq,
            long resultingRevision,
            String operationType,
            Map<String, Object> operation) {
        return insert(roomId, userId, connectionId, operationId, clientSessionId, clientSeq, baseRevision,
                roomSeq, resultingRevision, operationType, operation, null, null);
    }

    public OperationRecord insert(
            UUID roomId,
            UUID userId,
            String connectionId,
            String operationId,
            String clientSessionId,
            long clientSeq,
            long baseRevision,
            long roomSeq,
            long resultingRevision,
            String operationType,
            Map<String, Object> operation,
            String ownerNodeId,
            Long fencingToken) {
        return jdbcTemplate.queryForObject("""
                insert into room_operations (
                    id, room_id, user_id, connection_id, operation_id, client_session_id, client_seq,
                    base_revision, room_seq, resulting_revision, operation_type, operation_json,
                    owner_node_id, fencing_token
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?)
                returning id, room_id, user_id, connection_id, operation_id, client_session_id, client_seq,
                          base_revision, room_seq, resulting_revision, operation_type, operation_json, owner_node_id, fencing_token, created_at
                """, rowMapper, UUID.randomUUID(), roomId, userId, connectionId, operationId, clientSessionId,
                clientSeq, baseRevision, roomSeq, resultingRevision, operationType, writeJson(operation),
                ownerNodeId, fencingToken);
    }

    public Optional<OperationRecord> findByRoomAndOperationId(UUID roomId, String operationId) {
        List<OperationRecord> records = jdbcTemplate.query("""
                select id, room_id, user_id, connection_id, operation_id, client_session_id, client_seq,
                       base_revision, room_seq, resulting_revision, operation_type, operation_json, owner_node_id, fencing_token, created_at
                from room_operations
                where room_id = ? and operation_id = ?
                """, rowMapper, roomId, operationId);
        return records.stream().findFirst();
    }

    public Optional<OperationRecord> findByRoomSeq(UUID roomId, long roomSeq) {
        List<OperationRecord> records = jdbcTemplate.query("""
                select id, room_id, user_id, connection_id, operation_id, client_session_id, client_seq,
                       base_revision, room_seq, resulting_revision, operation_type, operation_json, owner_node_id, fencing_token, created_at
                from room_operations
                where room_id = ? and room_seq = ?
                """, rowMapper, roomId, roomSeq);
        return records.stream().findFirst();
    }

    public boolean existsOperationIdOutsideRoom(String operationId, UUID roomId) {
        Long count = jdbcTemplate.queryForObject("""
                select count(*)
                from room_operations
                where operation_id = ? and room_id <> ?
                """, Long.class, operationId, roomId);
        return count != null && count > 0;
    }

    public List<OperationRecord> findByRoom(UUID roomId) {
        return jdbcTemplate.query("""
                select id, room_id, user_id, connection_id, operation_id, client_session_id, client_seq,
                       base_revision, room_seq, resulting_revision, operation_type, operation_json, owner_node_id, fencing_token, created_at
                from room_operations
                where room_id = ?
                order by room_seq asc
                """, rowMapper, roomId);
    }

    public List<OperationRecord> findByRoomAfterRevision(UUID roomId, long baseRevision) {
        return jdbcTemplate.query("""
                select id, room_id, user_id, connection_id, operation_id, client_session_id, client_seq,
                       base_revision, room_seq, resulting_revision, operation_type, operation_json, owner_node_id, fencing_token, created_at
                from room_operations
                where room_id = ? and resulting_revision > ?
                order by room_seq asc
                """, rowMapper, roomId, baseRevision);
    }

    public List<OperationRecord> findByRoomAfterRoomSeq(UUID roomId, long roomSeq) {
        return jdbcTemplate.query("""
                select id, room_id, user_id, connection_id, operation_id, client_session_id, client_seq,
                       base_revision, room_seq, resulting_revision, operation_type, operation_json, owner_node_id, fencing_token, created_at
                from room_operations
                where room_id = ? and room_seq > ?
                order by room_seq asc
                """, rowMapper, roomId, roomSeq);
    }

    public List<OperationRecord> findActiveByRoomAfterRoomSeq(UUID roomId, long roomSeq) {
        return jdbcTemplate.query("""
                select id, room_id, user_id, connection_id, operation_id, client_session_id, client_seq,
                       base_revision, room_seq, resulting_revision, operation_type, operation_json, owner_node_id, fencing_token, created_at
                from room_operations
                where room_id = ? and room_seq > ? and compacted = false
                order by room_seq asc
                """, rowMapper, roomId, roomSeq);
    }

    public int markCompactedThroughRoomSeq(UUID roomId, long roomSeq, UUID compactionRunId) {
        return jdbcTemplate.update("""
                update room_operations
                set compacted = true,
                    compacted_at = ?,
                    compaction_run_id = ?
                where room_id = ?
                  and room_seq <= ?
                  and compacted = false
                """, OffsetDateTime.now(), compactionRunId, roomId, roomSeq);
    }

    public long countActiveAfterRoomSeq(UUID roomId, long roomSeq) {
        Long count = jdbcTemplate.queryForObject("""
                select count(*)
                from room_operations
                where room_id = ? and room_seq > ? and compacted = false
                """, Long.class, roomId, roomSeq);
        return count == null ? 0 : count;
    }

    public long countActiveThroughRoomSeq(UUID roomId, long roomSeq) {
        Long count = jdbcTemplate.queryForObject("""
                select count(*)
                from room_operations
                where room_id = ? and room_seq <= ? and compacted = false
                """, Long.class, roomId, roomSeq);
        return count == null ? 0 : count;
    }

    public long countCompactedThroughRoomSeq(UUID roomId, long roomSeq) {
        Long count = jdbcTemplate.queryForObject("""
                select count(*)
                from room_operations
                where room_id = ? and room_seq <= ? and compacted = true
                """, Long.class, roomId, roomSeq);
        return count == null ? 0 : count;
    }

    public UUID recordCompactionRun(
            UUID id,
            UUID roomId,
            long minimumResumableRoomSeq,
            long snapshotRoomSeq,
            int compactedCount,
            int activeTailCount) {
        jdbcTemplate.update("""
                insert into room_operation_compaction_runs (
                    id, room_id, minimum_resumable_room_seq, snapshot_room_seq,
                    compacted_count, active_tail_count, status
                )
                values (?, ?, ?, ?, ?, ?, 'COMPLETED')
                """, id, roomId, minimumResumableRoomSeq, snapshotRoomSeq, compactedCount, activeTailCount);
        return id;
    }

    public Optional<CompactionRunRecord> findLatestCompactionRun(UUID roomId) {
        return jdbcTemplate.query("""
                select id, room_id, minimum_resumable_room_seq, snapshot_room_seq,
                       compacted_count, active_tail_count, status, created_at
                from room_operation_compaction_runs
                where room_id = ?
                order by created_at desc, id desc
                limit 1
                """, this::mapCompactionRun, roomId).stream().findFirst();
    }

    public long maxRoomSeq(UUID roomId) {
        Long maxRoomSeq = jdbcTemplate.queryForObject("""
                select coalesce(max(room_seq), 0)
                from room_operations
                where room_id = ?
                """, Long.class, roomId);
        return maxRoomSeq == null ? 0 : maxRoomSeq;
    }

    public boolean sameOperation(OperationRecord record, String operationType, Map<String, Object> operation) {
        return record.operationType().equals(operationType)
                && objectMapper.valueToTree(record.operation()).equals(objectMapper.valueToTree(operation));
    }

    private OperationRecord mapOperation(ResultSet rs, int rowNum) throws SQLException {
        return new OperationRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("room_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("connection_id"),
                rs.getString("operation_id"),
                rs.getString("client_session_id"),
                rs.getLong("client_seq"),
                rs.getLong("base_revision"),
                rs.getLong("room_seq"),
                rs.getLong("resulting_revision"),
                rs.getString("operation_type"),
                readMap(rs.getString("operation_json")),
                rs.getString("owner_node_id"),
                rs.getObject("fencing_token", Long.class),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private CompactionRunRecord mapCompactionRun(ResultSet rs, int rowNum) throws SQLException {
        return new CompactionRunRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("room_id", UUID.class),
                rs.getLong("minimum_resumable_room_seq"),
                rs.getLong("snapshot_room_seq"),
                rs.getInt("compacted_count"),
                rs.getInt("active_tail_count"),
                rs.getString("status"),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private Map<String, Object> readMap(String json) {
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to deserialize operation JSON", exception);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize operation JSON", exception);
        }
    }

    public record CompactionRunRecord(
            UUID id,
            UUID roomId,
            long minimumResumableRoomSeq,
            long snapshotRoomSeq,
            int compactedCount,
            int activeTailCount,
            String status,
            OffsetDateTime createdAt
    ) {
    }
}
