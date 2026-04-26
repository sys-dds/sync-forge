package com.syncforge.api.documentstate.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.syncforge.api.documentstate.model.DocumentLiveState;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class DocumentStateRepository {
    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<DocumentLiveState> rowMapper = this::mapState;

    public DocumentStateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public DocumentLiveState initializeIfMissing(UUID roomId, UUID documentId, String checksum) {
        return jdbcTemplate.queryForObject("""
                insert into document_live_states (
                    id, room_id, document_id, current_room_seq, current_revision, content_text, content_checksum
                )
                values (?, ?, ?, 0, 0, '', ?)
                on conflict (room_id) do update
                set room_id = excluded.room_id
                returning id, room_id, document_id, current_room_seq, current_revision, content_text,
                          content_checksum, last_operation_id, rebuilt_from_snapshot_id, rebuilt_at, created_at, updated_at
                """, rowMapper, UUID.randomUUID(), roomId, documentId, checksum);
    }

    public Optional<DocumentLiveState> findByRoomId(UUID roomId) {
        List<DocumentLiveState> states = jdbcTemplate.query("""
                select id, room_id, document_id, current_room_seq, current_revision, content_text,
                       content_checksum, last_operation_id, rebuilt_from_snapshot_id, rebuilt_at, created_at, updated_at
                from document_live_states
                where room_id = ?
                """, rowMapper, roomId);
        return states.stream().findFirst();
    }

    public DocumentLiveState updateState(
            UUID roomId,
            long roomSeq,
            long revision,
            String content,
            String checksum,
            UUID lastOperationId,
            UUID rebuiltFromSnapshotId,
            OffsetDateTime rebuiltAt) {
        return jdbcTemplate.queryForObject("""
                update document_live_states
                set current_room_seq = ?,
                    current_revision = ?,
                    content_text = ?,
                    content_checksum = ?,
                    last_operation_id = ?,
                    rebuilt_from_snapshot_id = ?,
                    rebuilt_at = ?,
                    updated_at = ?
                where room_id = ?
                returning id, room_id, document_id, current_room_seq, current_revision, content_text,
                          content_checksum, last_operation_id, rebuilt_from_snapshot_id, rebuilt_at, created_at, updated_at
                """, rowMapper, roomSeq, revision, content, checksum, lastOperationId, rebuiltFromSnapshotId,
                rebuiltAt, OffsetDateTime.now(), roomId);
    }

    public UUID startRebuild(UUID roomId, UUID documentId, UUID snapshotId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into document_state_rebuild_runs (id, room_id, document_id, status, from_snapshot_id)
                values (?, ?, ?, 'STARTED', ?)
                """, id, roomId, documentId, snapshotId);
        return id;
    }

    public void completeRebuild(UUID rebuildId, int operationsReplayed, DocumentLiveState state) {
        jdbcTemplate.update("""
                update document_state_rebuild_runs
                set completed_at = ?,
                    status = 'COMPLETED',
                    operations_replayed = ?,
                    resulting_revision = ?,
                    resulting_room_seq = ?,
                    resulting_checksum = ?
                where id = ?
                """, OffsetDateTime.now(), operationsReplayed, state.currentRevision(), state.currentRoomSeq(),
                state.contentChecksum(), rebuildId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failRebuild(UUID rebuildId, String message) {
        jdbcTemplate.update("""
                update document_state_rebuild_runs
                set completed_at = ?, status = 'FAILED', error_message = ?
                where id = ?
                """, OffsetDateTime.now(), message, rebuildId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailedRebuild(UUID roomId, UUID documentId, UUID snapshotId, String message) {
        jdbcTemplate.update("""
                insert into document_state_rebuild_runs (
                    id, room_id, document_id, completed_at, status, from_snapshot_id, error_message
                )
                values (?, ?, ?, ?, 'FAILED', ?, ?)
                """, UUID.randomUUID(), roomId, documentId, OffsetDateTime.now(), snapshotId, message);
    }

    private DocumentLiveState mapState(ResultSet rs, int rowNum) throws SQLException {
        return new DocumentLiveState(
                rs.getObject("id", UUID.class),
                rs.getObject("room_id", UUID.class),
                rs.getObject("document_id", UUID.class),
                rs.getLong("current_room_seq"),
                rs.getLong("current_revision"),
                rs.getString("content_text"),
                rs.getString("content_checksum"),
                rs.getObject("last_operation_id", UUID.class),
                rs.getObject("rebuilt_from_snapshot_id", UUID.class),
                rs.getObject("rebuilt_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }
}
