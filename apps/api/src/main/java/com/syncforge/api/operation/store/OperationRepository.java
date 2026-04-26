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
        return jdbcTemplate.queryForObject("""
                insert into room_operations (
                    id, room_id, user_id, connection_id, operation_id, client_session_id, client_seq,
                    base_revision, room_seq, resulting_revision, operation_type, operation_json
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb))
                returning id, room_id, user_id, connection_id, operation_id, client_session_id, client_seq,
                          base_revision, room_seq, resulting_revision, operation_type, operation_json, created_at
                """, rowMapper, UUID.randomUUID(), roomId, userId, connectionId, operationId, clientSessionId,
                clientSeq, baseRevision, roomSeq, resultingRevision, operationType, writeJson(operation));
    }

    public Optional<OperationRecord> findByRoomAndOperationId(UUID roomId, String operationId) {
        List<OperationRecord> records = jdbcTemplate.query("""
                select id, room_id, user_id, connection_id, operation_id, client_session_id, client_seq,
                       base_revision, room_seq, resulting_revision, operation_type, operation_json, created_at
                from room_operations
                where room_id = ? and operation_id = ?
                """, rowMapper, roomId, operationId);
        return records.stream().findFirst();
    }

    public List<OperationRecord> findByRoom(UUID roomId) {
        return jdbcTemplate.query("""
                select id, room_id, user_id, connection_id, operation_id, client_session_id, client_seq,
                       base_revision, room_seq, resulting_revision, operation_type, operation_json, created_at
                from room_operations
                where room_id = ?
                order by room_seq asc
                """, rowMapper, roomId);
    }

    public List<OperationRecord> findByRoomAfterRevision(UUID roomId, long baseRevision) {
        return jdbcTemplate.query("""
                select id, room_id, user_id, connection_id, operation_id, client_session_id, client_seq,
                       base_revision, room_seq, resulting_revision, operation_type, operation_json, created_at
                from room_operations
                where room_id = ? and resulting_revision > ?
                order by room_seq asc
                """, rowMapper, roomId, baseRevision);
    }

    public List<OperationRecord> findByRoomAfterRoomSeq(UUID roomId, long roomSeq) {
        return jdbcTemplate.query("""
                select id, room_id, user_id, connection_id, operation_id, client_session_id, client_seq,
                       base_revision, room_seq, resulting_revision, operation_type, operation_json, created_at
                from room_operations
                where room_id = ? and room_seq > ?
                order by room_seq asc
                """, rowMapper, roomId, roomSeq);
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
}
