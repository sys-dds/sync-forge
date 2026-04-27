package com.syncforge.api.text.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.syncforge.api.text.model.TextAtom;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class TextConvergenceRepository {
    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<TextAtom> rowMapper = this::mapAtom;

    public TextConvergenceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean insertAtom(
            UUID roomId,
            String atomId,
            String operationId,
            long roomSeq,
            long revision,
            int spanIndex,
            String anchorAtomId,
            String content,
            String orderingKey) {
        try {
            return jdbcTemplate.update("""
                    insert into collaborative_text_atoms (
                        room_id, atom_id, operation_id, room_seq, revision, span_index,
                        anchor_atom_id, content, ordering_key
                    )
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, roomId, atomId, operationId, roomSeq, revision, spanIndex, anchorAtomId, content, orderingKey) == 1;
        } catch (DuplicateKeyException exception) {
            return false;
        }
    }

    public Optional<TextAtom> findAtom(UUID roomId, String atomId) {
        return jdbcTemplate.query("""
                select room_id, atom_id, operation_id, room_seq, revision, span_index, anchor_atom_id,
                       content, ordering_key, tombstoned, deleted_by_operation_id, deleted_at_room_seq,
                       created_at, updated_at
                from collaborative_text_atoms
                where room_id = ? and atom_id = ?
                """, rowMapper, roomId, atomId).stream().findFirst();
    }

    public boolean atomExists(UUID roomId, String atomId) {
        Long count = jdbcTemplate.queryForObject("""
                select count(*)
                from collaborative_text_atoms
                where room_id = ? and atom_id = ?
                """, Long.class, roomId, atomId);
        return count != null && count > 0;
    }

    public List<TextAtom> listRoomAtoms(UUID roomId) {
        return jdbcTemplate.query("""
                select room_id, atom_id, operation_id, room_seq, revision, span_index, anchor_atom_id,
                       content, ordering_key, tombstoned, deleted_by_operation_id, deleted_at_room_seq,
                       created_at, updated_at
                from collaborative_text_atoms
                where room_id = ?
                order by ordering_key, room_seq, atom_id
                """, rowMapper, roomId);
    }

    public void replaceSnapshotAtoms(UUID snapshotId, UUID roomId) {
        jdbcTemplate.update("delete from document_snapshot_text_atoms where snapshot_id = ?", snapshotId);
        List<TextAtom> atoms = listRoomAtoms(roomId);
        for (TextAtom atom : atoms) {
            jdbcTemplate.update("""
                    insert into document_snapshot_text_atoms (
                        snapshot_id, room_id, atom_id, operation_id, room_seq, revision, span_index,
                        anchor_atom_id, content, ordering_key, tombstoned, deleted_by_operation_id,
                        deleted_at_room_seq, created_at, updated_at
                    )
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    snapshotId,
                    atom.roomId(),
                    atom.atomId(),
                    atom.operationId(),
                    atom.roomSeq(),
                    atom.revision(),
                    atom.spanIndex(),
                    atom.anchorAtomId(),
                    atom.content(),
                    atom.orderingKey(),
                    atom.tombstoned(),
                    atom.deletedByOperationId(),
                    atom.deletedAtRoomSeq(),
                    atom.createdAt(),
                    atom.updatedAt());
        }
    }

    public List<TextAtom> listSnapshotAtoms(UUID snapshotId) {
        return jdbcTemplate.query("""
                select room_id, atom_id, operation_id, room_seq, revision, span_index, anchor_atom_id,
                       content, ordering_key, tombstoned, deleted_by_operation_id, deleted_at_room_seq,
                       created_at, updated_at
                from document_snapshot_text_atoms
                where snapshot_id = ?
                order by ordering_key, room_seq, atom_id
                """, rowMapper, snapshotId);
    }

    public int markTombstoned(UUID roomId, List<String> atomIds, String operationId, long roomSeq) {
        if (atomIds.isEmpty()) {
            return 0;
        }
        String placeholders = String.join(", ", java.util.Collections.nCopies(atomIds.size(), "?"));
        List<Object> args = new java.util.ArrayList<>();
        args.add(operationId);
        args.add(roomSeq);
        args.add(OffsetDateTime.now());
        args.add(roomId);
        args.addAll(atomIds);
        return jdbcTemplate.update("""
                update collaborative_text_atoms
                set tombstoned = true,
                    deleted_by_operation_id = ?,
                    deleted_at_room_seq = ?,
                    updated_at = ?
                where room_id = ?
                  and atom_id in (%s)
                  and tombstoned = false
                """.formatted(placeholders), args.toArray());
    }

    public long countRoomAtoms(UUID roomId) {
        Long count = jdbcTemplate.queryForObject("""
                select count(*)
                from collaborative_text_atoms
                where room_id = ?
                """, Long.class, roomId);
        return count == null ? 0 : count;
    }

    private TextAtom mapAtom(ResultSet rs, int rowNum) throws SQLException {
        return new TextAtom(
                rs.getObject("room_id", UUID.class),
                rs.getString("atom_id"),
                rs.getString("operation_id"),
                rs.getLong("room_seq"),
                rs.getLong("revision"),
                rs.getInt("span_index"),
                rs.getString("anchor_atom_id"),
                rs.getString("content"),
                rs.getString("ordering_key"),
                rs.getBoolean("tombstoned"),
                rs.getString("deleted_by_operation_id"),
                rs.getObject("deleted_at_room_seq", Long.class),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }
}
