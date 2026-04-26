package com.syncforge.api.protocol;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncforge.api.capability.CapabilityNegotiationResult;
import com.syncforge.api.capability.ClientCapability;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ProtocolSessionRepository {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RowMapper<ProtocolSession> rowMapper = this::map;

    public ProtocolSessionRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void createNegotiated(
            String connectionId,
            String websocketSessionId,
            UUID roomId,
            UUID userId,
            String clientId,
            String deviceId,
            String clientSessionId,
            ProtocolVersionNegotiationResult protocol,
            CapabilityNegotiationResult capabilities) {
        jdbcTemplate.update("""
                insert into websocket_protocol_sessions (
                    connection_id, websocket_session_id, room_id, user_id, client_id, device_id, client_session_id,
                    requested_protocol_version, negotiated_protocol_version, server_preferred_protocol_version,
                    legacy_default_applied, enabled_capabilities_json, disabled_capabilities_json,
                    rejected_capabilities_json, status
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, 'NEGOTIATED')
                """,
                connectionId,
                websocketSessionId,
                roomId,
                userId,
                clientId,
                deviceId,
                clientSessionId,
                protocol.requestedProtocolVersion(),
                protocol.negotiatedProtocolVersion(),
                protocol.serverPreferredProtocolVersion(),
                protocol.legacyDefaultApplied(),
                toJson(capabilities.enabledCapabilities().stream().map(ClientCapability::name).toList()),
                toJson(capabilities.disabledCapabilities()),
                toJson(capabilities.rejectedCapabilities()));
    }

    public Optional<ProtocolSession> findActiveByConnectionId(String connectionId) {
        return jdbcTemplate.query("""
                select connection_id, websocket_session_id, room_id, user_id, client_id, device_id, client_session_id,
                       requested_protocol_version, negotiated_protocol_version, server_preferred_protocol_version,
                       legacy_default_applied, enabled_capabilities_json, status, created_at, updated_at, closed_at
                from websocket_protocol_sessions
                where connection_id = ? and status = 'NEGOTIATED'
                """, rowMapper, connectionId).stream().findFirst();
    }

    public Optional<ProtocolSession> findByConnectionId(String connectionId) {
        return jdbcTemplate.query("""
                select connection_id, websocket_session_id, room_id, user_id, client_id, device_id, client_session_id,
                       requested_protocol_version, negotiated_protocol_version, server_preferred_protocol_version,
                       legacy_default_applied, enabled_capabilities_json, status, created_at, updated_at, closed_at
                from websocket_protocol_sessions
                where connection_id = ?
                """, rowMapper, connectionId).stream().findFirst();
    }

    public void markClosed(String connectionId) {
        jdbcTemplate.update("""
                update websocket_protocol_sessions
                set status = 'CLOSED', closed_at = coalesce(closed_at, now()), updated_at = now()
                where connection_id = ? and status = 'NEGOTIATED'
                """, connectionId);
    }

    private ProtocolSession map(ResultSet rs, int rowNum) throws SQLException {
        return new ProtocolSession(
                rs.getString("connection_id"),
                rs.getString("websocket_session_id"),
                rs.getObject("room_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("client_id"),
                rs.getString("device_id"),
                rs.getString("client_session_id"),
                (Integer) rs.getObject("requested_protocol_version"),
                rs.getInt("negotiated_protocol_version"),
                rs.getInt("server_preferred_protocol_version"),
                rs.getBoolean("legacy_default_applied"),
                enabledCapabilities(rs.getString("enabled_capabilities_json")),
                rs.getString("status"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class),
                rs.getObject("closed_at", OffsetDateTime.class));
    }

    private Set<ClientCapability> enabledCapabilities(String rawJson) {
        try {
            LinkedHashSet<ClientCapability> capabilities = new LinkedHashSet<>();
            for (String raw : objectMapper.readValue(rawJson, STRING_LIST)) {
                ClientCapability.parse(raw).ifPresent(capabilities::add);
            }
            return capabilities;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not parse enabled capabilities", exception);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize protocol session capabilities", exception);
        }
    }
}
