package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import com.syncforge.api.operation.application.CanonicalOperationPayloadHasher;
import com.syncforge.api.operation.application.OperationService;
import com.syncforge.api.operation.model.OperationSubmitResult;
import com.syncforge.api.operation.model.SubmitOperationCommand;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class Pr8CarryForwardRecoveryIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    OperationService operationService;

    @Autowired
    CanonicalOperationPayloadHasher payloadHasher;

    @Test
    void recoveryBaselinePreservesOfflineDeliveryAndPermissionSafety() {
        Fixture fixture = fixture();
        Map<String, Object> payload = Map.of("text", "A");

        OperationSubmitResult accepted = operationService.submit(new SubmitOperationCommand(
                fixture.roomId(), fixture.editorId(), "pr8-connection", "pr8-session", "pr8-offline-text",
                1L, 0L, "TEXT_INSERT_AFTER", payload, true, "pr8-client-op", 0L, null, java.util.List.of(),
                payloadHasher.hash("TEXT_INSERT_AFTER", payload)));
        jdbcTemplate.update("""
                update room_memberships
                set status = 'REMOVED'
                where room_id = ? and user_id = ?
                """, fixture.roomId(), fixture.editorId());
        OperationSubmitResult denied = operationService.submit(new SubmitOperationCommand(
                fixture.roomId(), fixture.editorId(), "pr8-denied-connection", "pr8-denied-session",
                "pr8-denied-text", 2L, 1L, "TEXT_INSERT_AFTER", Map.of("anchorAtomId", "pr8-offline-text:0", "text", "B"),
                true, "pr8-denied-client-op", 1L, null, java.util.List.of(),
                payloadHasher.hash("TEXT_INSERT_AFTER", Map.of("anchorAtomId", "pr8-offline-text:0", "text", "B"))));

        Long outboxRows = jdbcTemplate.queryForObject("select count(*) from room_event_outbox where room_id = ?",
                Long.class, fixture.roomId());
        assertThat(accepted.accepted()).isTrue();
        assertThat(denied.accepted()).isFalse();
        assertThat(denied.code()).isEqualTo("EDIT_PERMISSION_REQUIRED");
        assertThat(outboxRows).isEqualTo(1);
    }
}
