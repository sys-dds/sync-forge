package com.syncforge.api.workspace.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import com.syncforge.api.workspace.model.Workspace;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class WorkspaceRepository {
    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<Workspace> rowMapper = this::mapWorkspace;

    public WorkspaceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Workspace create(String workspaceKey, String name) {
        UUID id = UUID.randomUUID();
        try {
            return jdbcTemplate.queryForObject("""
                    insert into workspaces (id, workspace_key, name, status)
                    values (?, ?, ?, 'ACTIVE')
                    returning id, workspace_key, name, status, created_at, updated_at
                    """, rowMapper, id, workspaceKey, name);
        } catch (DuplicateKeyException exception) {
            throw exception;
        }
    }

    public Optional<Workspace> findById(UUID id) {
        return jdbcTemplate.query("""
                select id, workspace_key, name, status, created_at, updated_at
                from workspaces
                where id = ?
                """, rowMapper, id).stream().findFirst();
    }

    public boolean existsById(UUID id) {
        Boolean exists = jdbcTemplate.queryForObject("select exists(select 1 from workspaces where id = ?)", Boolean.class, id);
        return Boolean.TRUE.equals(exists);
    }

    private Workspace mapWorkspace(ResultSet rs, int rowNum) throws SQLException {
        return new Workspace(
                rs.getObject("id", UUID.class),
                rs.getString("workspace_key"),
                rs.getString("name"),
                rs.getString("status"),
                rs.getObject("created_at", java.time.OffsetDateTime.class),
                rs.getObject("updated_at", java.time.OffsetDateTime.class));
    }
}
