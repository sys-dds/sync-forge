package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SyncForgeFinalBackendFeatureStopLineIntegrationTest extends RuntimeControlTestSupport {
    @Test
    void finalBackendRuntimeApisExistAndCoreInvariantsPass() {
        Fixture fixture = fixture();
        insert(fixture, "stop-a", 1, 0, "START", "A");
        snapshot(fixture);

        assertThat(runtimeGet(fixture, "/health")).containsKey("status");
        assertThat(runtimeGet(fixture, "/invariants")).containsEntry("status", "PASS");
        assertThat(runtimeGet(fixture, "/delivery")).containsKey("deliveryStatus");
        assertThat(runtimeGet(fixture, "/ownership-audit")).containsEntry("status", "PASS");
        assertThat(runtimeGet(fixture, "")).containsKey("recommendedAction");
    }
}
