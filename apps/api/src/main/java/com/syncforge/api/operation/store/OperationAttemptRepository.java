package com.syncforge.api.operation.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncforge.api.operation.model.OperationAttempt;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class OperationAttemptRepository {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RowMapper<OperationAttempt> rowMapper = this::mapAttempt;

    public OperationAttemptRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void record(
            UUID roomId,
            UUID userId,
            String connectionId,
            String operationId,
            long clientSeq,
            long baseRevision,
            String operationType,
            Map<String, Object> operation,
            String outcome,
            String nackCode,
            String message,
            Long roomSeq,
            Long revision,
            UUID duplicateOfOperationId) {
        jdbcTemplate.update("""
                insert into room_operation_attempts (
                    id, room_id, user_id, connection_id, operation_id, client_seq, base_revision,
                    operation_type, operation_json, outcome, nack_code, message, assigned_room_seq,
                    resulting_revision, duplicate_of_operation_id
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), roomId, userId, connectionId, operationId, clientSeq, baseRevision,
                operationType, writeJson(operation), outcome, nackCode, message, roomSeq, revision, duplicateOfOperationId);
    }

    public List<OperationAttempt> findByRoom(UUID roomId) {
        return jdbcTemplate.query("""
                select id, room_id, user_id, connection_id, operation_id, client_seq, base_revision,
                       operation_type, operation_json, outcome, nack_code, message, assigned_room_seq,
                       resulting_revision, duplicate_of_operation_id, created_at
                from room_operation_attempts
                where room_id = ?
                order by created_at asc, id asc
                """, rowMapper, roomId);
    }

    private OperationAttempt mapAttempt(ResultSet rs, int rowNum) throws SQLException {
        return new OperationAttempt(
                rs.getObject("id", UUID.class),
                rs.getObject("room_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("connection_id"),
                rs.getString("operation_id"),
                rs.getLong("client_seq"),
                rs.getLong("base_revision"),
                rs.getString("operation_type"),
                readMap(rs.getString("operation_json")),
                rs.getString("outcome"),
                rs.getString("nack_code"),
                rs.getString("message"),
                longOrNull(rs, "assigned_room_seq"),
                longOrNull(rs, "resulting_revision"),
                rs.getObject("duplicate_of_operation_id", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private Long longOrNull(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private Map<String, Object> readMap(String json) {
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to deserialize operation attempt JSON", exception);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize operation attempt JSON", exception);
        }
    }
}
