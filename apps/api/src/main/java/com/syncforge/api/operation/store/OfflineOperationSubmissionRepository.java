package com.syncforge.api.operation.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncforge.api.operation.model.OfflineOperationSubmission;
import com.syncforge.api.operation.model.OfflineOperationSubmissionStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class OfflineOperationSubmissionRepository {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RowMapper<OfflineOperationSubmission> rowMapper = this::map;

    public OfflineOperationSubmissionRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public OfflineOperationSubmission recordAccepted(
            UUID roomId,
            UUID userId,
            String clientId,
            String clientOperationId,
            long baseRoomSeq,
            long baseRevision,
            String canonicalPayloadHash,
            List<String> causalDependencies,
            String acceptedOperationId,
            long acceptedRoomSeq) {
        insert(roomId, userId, clientId, clientOperationId, baseRoomSeq, baseRevision, canonicalPayloadHash,
                causalDependencies, OfflineOperationSubmissionStatus.ACCEPTED, acceptedOperationId, acceptedRoomSeq,
                null, null);
        return findByClientOperation(roomId, userId, clientOperationId).orElseThrow();
    }

    public OfflineOperationSubmission recordRejected(
            UUID roomId,
            UUID userId,
            String clientId,
            String clientOperationId,
            long baseRoomSeq,
            long baseRevision,
            String canonicalPayloadHash,
            List<String> causalDependencies,
            String rejectionCode,
            String rejectionReason) {
        insert(roomId, userId, clientId, clientOperationId, baseRoomSeq, baseRevision, canonicalPayloadHash,
                causalDependencies, OfflineOperationSubmissionStatus.REJECTED, null, null, rejectionCode, rejectionReason);
        return findByClientOperation(roomId, userId, clientOperationId).orElseThrow();
    }

    public Optional<OfflineOperationSubmission> findByClientOperation(UUID roomId, UUID userId, String clientOperationId) {
        return jdbcTemplate.query("""
                select *
                from offline_operation_submissions
                where room_id = ? and user_id = ? and client_operation_id = ?
                """, rowMapper, roomId, userId, clientOperationId).stream().findFirst();
    }

    public long countByRoom(UUID roomId) {
        Long count = jdbcTemplate.queryForObject("""
                select count(*)
                from offline_operation_submissions
                where room_id = ?
                """, Long.class, roomId);
        return count == null ? 0 : count;
    }

    private void insert(
            UUID roomId,
            UUID userId,
            String clientId,
            String clientOperationId,
            long baseRoomSeq,
            long baseRevision,
            String canonicalPayloadHash,
            List<String> causalDependencies,
            OfflineOperationSubmissionStatus status,
            String acceptedOperationId,
            Long acceptedRoomSeq,
            String rejectionCode,
            String rejectionReason) {
        jdbcTemplate.update("""
                insert into offline_operation_submissions (
                    id, room_id, user_id, client_id, client_operation_id, base_room_seq, base_revision,
                    canonical_payload_hash, causal_dependencies_json, status, accepted_operation_id,
                    accepted_room_seq, rejection_code, rejection_reason
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
                on conflict (room_id, user_id, client_operation_id) do nothing
                """, UUID.randomUUID(), roomId, userId, clientId, clientOperationId, baseRoomSeq, baseRevision,
                canonicalPayloadHash, writeDependencies(causalDependencies), status.name(), acceptedOperationId,
                acceptedRoomSeq, rejectionCode, rejectionReason);
    }

    private OfflineOperationSubmission map(ResultSet rs, int rowNum) throws SQLException {
        return new OfflineOperationSubmission(
                rs.getObject("id", UUID.class),
                rs.getObject("room_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("client_id"),
                rs.getString("client_operation_id"),
                rs.getLong("base_room_seq"),
                rs.getLong("base_revision"),
                rs.getString("canonical_payload_hash"),
                readDependencies(rs.getString("causal_dependencies_json")),
                OfflineOperationSubmissionStatus.valueOf(rs.getString("status")),
                rs.getString("accepted_operation_id"),
                longOrNull(rs, "accepted_room_seq"),
                rs.getString("rejection_code"),
                rs.getString("rejection_reason"),
                rs.getObject("created_at", java.time.OffsetDateTime.class),
                rs.getObject("updated_at", java.time.OffsetDateTime.class));
    }

    private Long longOrNull(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private String writeDependencies(List<String> dependencies) {
        try {
            return objectMapper.writeValueAsString(dependencies == null ? List.of() : dependencies);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize offline operation dependencies", exception);
        }
    }

    private List<String> readDependencies(String dependencies) {
        try {
            return objectMapper.readValue(dependencies, STRING_LIST);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to deserialize offline operation dependencies", exception);
        }
    }
}
