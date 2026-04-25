package com.syncforge.api.identity.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import com.syncforge.api.identity.model.User;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {
    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<User> rowMapper = this::mapUser;

    public UserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public User create(String externalUserKey, String displayName) {
        UUID id = UUID.randomUUID();
        return jdbcTemplate.queryForObject("""
                insert into users (id, external_user_key, display_name, status)
                values (?, ?, ?, 'ACTIVE')
                returning id, external_user_key, display_name, status, created_at, updated_at
                """, rowMapper, id, externalUserKey, displayName);
    }

    public Optional<User> findById(UUID id) {
        return jdbcTemplate.query("""
                select id, external_user_key, display_name, status, created_at, updated_at
                from users
                where id = ?
                """, rowMapper, id).stream().findFirst();
    }

    public boolean existsById(UUID id) {
        Boolean exists = jdbcTemplate.queryForObject("select exists(select 1 from users where id = ?)", Boolean.class, id);
        return Boolean.TRUE.equals(exists);
    }

    private User mapUser(ResultSet rs, int rowNum) throws SQLException {
        return new User(
                rs.getObject("id", UUID.class),
                rs.getString("external_user_key"),
                rs.getString("display_name"),
                rs.getString("status"),
                rs.getObject("created_at", java.time.OffsetDateTime.class),
                rs.getObject("updated_at", java.time.OffsetDateTime.class));
    }
}
