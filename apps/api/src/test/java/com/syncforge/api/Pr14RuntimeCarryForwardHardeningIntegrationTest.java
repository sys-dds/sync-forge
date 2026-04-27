package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class Pr14RuntimeCarryForwardHardeningIntegrationTest extends JepsenLiteTestSupport {
    @Test
    void runtimeControlsVerifierPoisonDrainAndPermissionCarryForwardRemainHard() {
        Fixture fixture = fixture();
        insert(fixture, "pr14-a", 1, 0, "START", "A");
        step("insert", "accepted operation", operationCount(fixture.roomId()));

        runtimeControlService.pauseWrites(fixture.roomId(), fixture.ownerId(), "carry forward pause");
        assertThat(runtimeHealthService.health(fixture.roomId()).status().name()).isEqualTo("PAUSED");
        assertThat(submitText(fixture, "pr14-paused", 2, 1, "TEXT_INSERT_AFTER",
                java.util.Map.of("text", "blocked")).accepted()).isFalse();
        assertThat(operationCount(fixture.roomId())).isEqualTo(1);

        markFirstOutboxRetry(fixture, "carry-forward backlog");
        assertThat(deliveryRuntimeService.status(fixture.roomId()).deliveryStatus()).isEqualTo("RETRYING");
        assertThat(deliveryRuntimeService.drain(fixture.roomId()).attempted()).isLessThanOrEqualTo(100);

        poisonOperationService.quarantine(fixture.roomId(), "pr14-a", 1L, "carry-forward poison", fixture.ownerId());
        assertThat(runtimeHealthService.health(fixture.roomId()).status().name()).isEqualTo("REPAIR_REQUIRED");
        repairService.rebuildState(fixture.roomId(), fixture.ownerId(), "carry forward repair");
        assertThat(poisonOperationService.countQuarantined(fixture.roomId())).isZero();

        corruptVisibleTextOnly(fixture, "drift");
        assertInvariantFail(fixture, "TEXT_MATERIALIZATION_MISMATCH");
        assertForbiddenGet("/api/v1/rooms/" + fixture.roomId() + "/runtime/health?userId=" + fixture.viewerId());
    }
}
