package com.syncforge.api.document.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import com.syncforge.api.document.model.Document;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class DocumentRepository {
    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<Document> rowMapper = this::mapDocument;

    public DocumentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Document create(UUID workspaceId, String documentKey, String title) {
        UUID id = UUID.randomUUID();
        return jdbcTemplate.queryForObject("""
                insert into documents (id, workspace_id, document_key, title, status)
                values (?, ?, ?, ?, 'ACTIVE')
                returning id, workspace_id, document_key, title, status, created_at, updated_at
                """, rowMapper, id, workspaceId, documentKey, title);
    }

    public Optional<Document> findById(UUID id) {
        return jdbcTemplate.query("""
                select id, workspace_id, document_key, title, status, created_at, updated_at
                from documents
                where id = ?
                """, rowMapper, id).stream().findFirst();
    }

    private Document mapDocument(ResultSet rs, int rowNum) throws SQLException {
        return new Document(
                rs.getObject("id", UUID.class),
                rs.getObject("workspace_id", UUID.class),
                rs.getString("document_key"),
                rs.getString("title"),
                rs.getString("status"),
                rs.getObject("created_at", java.time.OffsetDateTime.class),
                rs.getObject("updated_at", java.time.OffsetDateTime.class));
    }
}
