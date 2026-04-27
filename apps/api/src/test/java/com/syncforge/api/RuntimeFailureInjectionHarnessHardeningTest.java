package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RuntimeFailureInjectionHarnessHardeningTest extends JepsenLiteTestSupport {
    @Test
    void testOnlyHooksDriveRuntimePathsAndNoPublicFaultEndpointExists() {
        Fixture fixture = fixture();
        insert(fixture, "harness-a", 1, 0, "START", "A");
        snapshot(fixture);
        TestFailureHarness harness = failureHarness(fixture);

        harness.failNextOutboxPublish();
        harness.drainWithInjectedPublishFailure();
        assertThat(deliveryRuntimeService.status(fixture.roomId()).deliveryStatus()).isIn("RETRYING", "HEALTHY");

        harness.failReplayOn("harness-a");
        harness.replayAndQuarantineIfInjected(fixture.ownerId());
        assertThat(poisonOperationService.countQuarantined(fixture.roomId())).isEqualTo(1);

        harness.emitDuplicateStreamEventWithoutStateChange();
        assertThat(operationCount(fixture.roomId())).isEqualTo(1);
        assertThat(restTemplate.getForEntity(baseUrl + "/api/v1/rooms/" + fixture.roomId()
                + "/runtime/fault-injection?userId=" + fixture.ownerId(), String.class).getStatusCode().is2xxSuccessful()).isFalse();
    }
}
