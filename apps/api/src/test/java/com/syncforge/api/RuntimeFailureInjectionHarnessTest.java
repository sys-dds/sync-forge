package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RuntimeFailureInjectionHarnessTest extends RuntimeControlTestSupport {
    @Test
    void failureInjectionIsTestOnlyAndRuntimeApiDoesNotExposeFaultEndpoint() {
        Fixture fixture = fixture();
        RuntimeFailureInjectionHarness harness = new RuntimeFailureInjectionHarness();
        harness.failReplayOn("operation-x");

        assertThat(harness.shouldFailReplay("operation-x")).isTrue();
        var response = restTemplate.getForEntity(baseUrl + "/api/v1/rooms/" + fixture.roomId()
                + "/runtime/fault-injection?userId=" + fixture.ownerId(), String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isFalse();
    }

    static final class RuntimeFailureInjectionHarness {
        private String replayOperationId;

        void failReplayOn(String operationId) {
            this.replayOperationId = operationId;
        }

        boolean shouldFailReplay(String operationId) {
            return operationId.equals(replayOperationId);
        }
    }
}
