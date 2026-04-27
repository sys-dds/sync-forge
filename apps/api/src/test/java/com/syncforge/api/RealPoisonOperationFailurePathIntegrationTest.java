package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RealPoisonOperationFailurePathIntegrationTest extends JepsenLiteTestSupport {
    @Test
    void injectedReplayFailureQuarantinesCanonicalOperationAndRepairClearOnlyOnSuccess() {
        Fixture fixture = fixture();
        insert(fixture, "poison-a", 1, 0, "START", "A");
        snapshot(fixture);

        TestFailureHarness harness = failureHarness(fixture);
        harness.failReplayOn("poison-a");
        harness.replayAndQuarantineIfInjected(fixture.ownerId());
        harness.replayAndQuarantineIfInjected(fixture.ownerId());

        assertThat(poisonOperationService.listQuarantined(fixture.roomId()).getFirst().failureCount()).isEqualTo(2);
        assertThat(runtimeHealthService.health(fixture.roomId()).status().name()).isEqualTo("REPAIR_REQUIRED");
        assertThat(operationRepository.findByRoomAndOperationId(fixture.roomId(), "poison-a")).isPresent();

        harness.failSnapshotReplayValidation();
        harness.repairExpectingFailure(fixture.ownerId());
        assertThat(poisonOperationService.countQuarantined(fixture.roomId())).isEqualTo(1);

        snapshot(fixture);
        repairService.rebuildState(fixture.roomId(), fixture.ownerId(), "poison repair success");
        assertThat(poisonOperationService.countQuarantined(fixture.roomId())).isZero();
    }
}
