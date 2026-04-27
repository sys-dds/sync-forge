package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JepsenLiteOutboxFailureIntegrationTest extends JepsenLiteTestSupport {
    @Test
    void outboxFailureRetryDrainPreservesNoLostOperationInvariant() {
        Fixture fixture = fixture();
        insert(fixture, "outbox-a", 1, 0, "START", "A");
        step("accepted operation", "canonical row and outbox row", operationCount(fixture.roomId()));

        TestFailureHarness harness = failureHarness(fixture);
        harness.failNextOutboxPublish();
        harness.drainWithInjectedPublishFailure();
        assertThat(operationRepository.findByRoomAndOperationId(fixture.roomId(), "outbox-a")).isPresent();
        assertThat(deliveryRuntimeService.status(fixture.roomId()).unpublishedAcceptedCount()).isGreaterThanOrEqualTo(0);

        deliveryRuntimeService.drain(fixture.roomId());
        assertThat(resumeWindowService.decide(fixture.roomId(), fixture.editorId(), 0).operations()).hasSize(1);
        assertRoomSeqSafe(fixture);
        assertInvariantPass(fixture);
    }
}
