package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JepsenLiteDuplicateStreamEventIntegrationTest extends JepsenLiteTestSupport {
    @Test
    void duplicateStreamDeliveryDoesNotDuplicateCanonicalStateOrBackfill() {
        Fixture fixture = fixture();
        insert(fixture, "dup-stream-a", 1, 0, "START", "A");
        deliveryRuntimeService.drain(fixture.roomId());

        failureHarness(fixture).emitDuplicateStreamEventWithoutStateChange();
        step("duplicate stream event", "canonical DB truth unchanged", operationCount(fixture.roomId()));

        assertThat(operationCount(fixture.roomId())).isEqualTo(1);
        assertThat(resumeWindowService.decide(fixture.roomId(), fixture.editorId(), 0).operations()).hasSize(1);
        assertThat(textConvergenceService.materializeVisibleText(fixture.roomId())).isEqualTo("A");
        assertRoomSeqSafe(fixture);
        assertInvariantPass(fixture);
    }
}
