package com.syncforge.api.snapshot.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.syncforge.api.snapshot.model.DocumentSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class SnapshotRepository {
    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<DocumentSnapshot> rowMapper = this::mapSnapshot;

    public SnapshotRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public DocumentSnapshot create(
            UUID roomId,
            UUID documentId,
            long roomSeq,
            long revision,
            String content,
            String checksum,
            String reason) {
        return jdbcTemplate.queryForObject("""
                insert into document_snapshots (
                    id, room_id, document_id, room_seq, revision, content_text, content_checksum, snapshot_reason
                )
                values (?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (room_id, revision) do update
                set content_text = excluded.content_text,
                    content_checksum = excluded.content_checksum,
                    snapshot_reason = excluded.snapshot_reason
                returning id, room_id, document_id, room_seq, revision, content_text, content_checksum, snapshot_reason, created_at
                """, rowMapper, UUID.randomUUID(), roomId, documentId, roomSeq, revision, content, checksum, reason);
    }

    public Optional<DocumentSnapshot> findLatest(UUID roomId) {
        List<DocumentSnapshot> snapshots = jdbcTemplate.query("""
                select id, room_id, document_id, room_seq, revision, content_text, content_checksum, snapshot_reason, created_at
                from document_snapshots
                where room_id = ?
                order by revision desc, created_at desc
                limit 1
                """, rowMapper, roomId);
        return snapshots.stream().findFirst();
    }

    private DocumentSnapshot mapSnapshot(ResultSet rs, int rowNum) throws SQLException {
        return new DocumentSnapshot(
                rs.getObject("id", UUID.class),
                rs.getObject("room_id", UUID.class),
                rs.getObject("document_id", UUID.class),
                rs.getLong("room_seq"),
                rs.getLong("revision"),
                rs.getString("content_text"),
                rs.getString("content_checksum"),
                rs.getString("snapshot_reason"),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}
