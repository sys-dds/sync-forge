package com.syncforge.api.runtime;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RoomRuntimeControlRepository {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public RoomRuntimeControlRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public RoomRuntimeControlState getOrCreate(UUID roomId) {
        return jdbcTemplate.queryForObject("""
                insert into room_runtime_controls (room_id)
                values (?)
                on conflict (room_id) do update
                set room_id = excluded.room_id
                returning room_id, writes_paused, force_resync_generation, force_resync_reason,
                          repair_required, last_control_action, last_control_reason, last_control_actor, updated_at
                """, this::mapState, roomId);
    }

    public Optional<RoomRuntimeControlState> find(UUID roomId) {
        return jdbcTemplate.query("""
                select room_id, writes_paused, force_resync_generation, force_resync_reason,
                       repair_required, last_control_action, last_control_reason, last_control_actor, updated_at
                from room_runtime_controls
                where room_id = ?
                """, this::mapState, roomId).stream().findFirst();
    }

    public RoomRuntimeControlState update(
            UUID roomId,
            boolean writesPaused,
            Long forceResyncGeneration,
            String forceResyncReason,
            boolean repairRequired,
            String action,
            String reason,
            UUID actorUserId) {
        return jdbcTemplate.queryForObject("""
                update room_runtime_controls
                set writes_paused = ?,
                    force_resync_generation = ?,
                    force_resync_reason = ?,
                    repair_required = ?,
                    last_control_action = ?,
                    last_control_reason = ?,
                    last_control_actor = ?,
                    updated_at = now()
                where room_id = ?
                returning room_id, writes_paused, force_resync_generation, force_resync_reason,
                          repair_required, last_control_action, last_control_reason, last_control_actor, updated_at
                """, this::mapState, writesPaused, forceResyncGeneration, forceResyncReason, repairRequired,
                action, reason, actorUserId, roomId);
    }

    public void recordEvent(
            UUID roomId,
            UUID actorUserId,
            String action,
            String reason,
            RoomRuntimeControlState previous,
            RoomRuntimeControlState current) {
        jdbcTemplate.update("""
                insert into room_runtime_control_events (
                    id, room_id, actor_user_id, action, reason, previous_state_json, new_state_json
                )
                values (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
                """, UUID.randomUUID(), roomId, actorUserId, action, reason, write(previous), write(current));
    }

    public long eventCount(UUID roomId, String action) {
        Long count = jdbcTemplate.queryForObject("""
                select count(*)
                from room_runtime_control_events
                where room_id = ? and action = ?
                """, Long.class, roomId, action);
        return count == null ? 0 : count;
    }

    private RoomRuntimeControlState mapState(ResultSet rs, int rowNum) throws SQLException {
        return new RoomRuntimeControlState(
                rs.getObject("room_id", UUID.class),
                rs.getBoolean("writes_paused"),
                rs.getLong("force_resync_generation"),
                rs.getString("force_resync_reason"),
                rs.getBoolean("repair_required"),
                rs.getString("last_control_action"),
                rs.getString("last_control_reason"),
                rs.getObject("last_control_actor", UUID.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    @SuppressWarnings("unused")
    private RoomRuntimeControlEvent mapEvent(ResultSet rs, int rowNum) throws SQLException {
        return new RoomRuntimeControlEvent(
                rs.getObject("id", UUID.class),
                rs.getObject("room_id", UUID.class),
                rs.getObject("actor_user_id", UUID.class),
                rs.getString("action"),
                rs.getString("reason"),
                read(rs.getString("previous_state_json")),
                read(rs.getString("new_state_json")),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private String write(RoomRuntimeControlState state) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "roomId", state.roomId().toString(),
                    "writesPaused", state.writesPaused(),
                    "forceResyncGeneration", state.forceResyncGeneration(),
                    "forceResyncReason", state.forceResyncReason() == null ? "" : state.forceResyncReason(),
                    "repairRequired", state.repairRequired(),
                    "lastControlAction", state.lastControlAction() == null ? "" : state.lastControlAction(),
                    "lastControlReason", state.lastControlReason() == null ? "" : state.lastControlReason(),
                    "lastControlActor", state.lastControlActor() == null ? "" : state.lastControlActor().toString(),
                    "updatedAt", state.updatedAt() == null ? "" : state.updatedAt().toString()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize runtime control state", exception);
        }
    }

    private Map<String, Object> read(String json) {
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to deserialize runtime control event state", exception);
        }
    }
}
