package com.syncforge.api.conflict.store;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncforge.api.operation.model.OperationRecord;
import com.syncforge.api.operation.model.SubmitOperationCommand;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ConflictTraceRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ConflictTraceRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void record(
            SubmitOperationCommand command,
            long currentRevision,
            String decision,
            String reason,
            List<OperationRecord> concurrentOperations,
            Map<String, Object> transformedOperation) {
        jdbcTemplate.update("""
                insert into room_conflict_resolution_traces (
                    id, room_id, operation_id, user_id, base_revision, current_revision, decision, reason,
                    incoming_operation_json, concurrent_operations_json, transformed_operation_json
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb), cast(? as jsonb))
                """, UUID.randomUUID(), command.roomId(), command.operationId(), command.userId(),
                command.baseRevision(), currentRevision, decision, reason, writeJson(command.operation()),
                writeJson(concurrentPayload(concurrentOperations)),
                transformedOperation == null ? null : writeJson(transformedOperation));
    }

    private List<Map<String, Object>> concurrentPayload(List<OperationRecord> records) {
        return records.stream()
                .map(record -> Map.of(
                        "operationId", record.operationId(),
                        "userId", record.userId().toString(),
                        "roomSeq", record.roomSeq(),
                        "revision", record.resultingRevision(),
                        "operationType", record.operationType(),
                        "operation", record.operation()))
                .toList();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize conflict trace JSON", exception);
        }
    }
}
