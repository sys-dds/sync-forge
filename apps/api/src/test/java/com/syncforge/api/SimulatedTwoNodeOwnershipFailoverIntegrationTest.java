package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

class SimulatedTwoNodeOwnershipFailoverIntegrationTest extends JepsenLiteTestSupport {
    @Test
    void nodeTakeoverRejectsOldTokensAndKeepsResumeCanonical() {
        Fixture fixture = fixture();
        var nodeA = acquire(fixture, NODE_A);
        assertThat(submitAs(fixture, NODE_A, nodeA.fencingToken(), "two-node-a", 1, 0, Map.of("text", "A")).accepted()).isTrue();
        var nodeB = takeover(fixture, NODE_B, 1);
        assertThat(submitAs(fixture, NODE_B, nodeB.fencingToken(), "two-node-b", 2, 1,
                Map.of("anchorAtomId", atomId("two-node-a", 0), "text", "B")).accepted()).isTrue();

        var stale = submitAs(fixture, NODE_A, nodeA.fencingToken(), "two-node-stale", 3, 2, Map.of("text", "X"));
        assertThat(stale.accepted()).isFalse();
        ownershipService.recordFencedPublishRejected(fixture.roomId(), NODE_A, nodeA.fencingToken());

        var sameNodeToken2 = takeover(fixture, NODE_B, 1);
        assertThat(submitAs(fixture, NODE_B, nodeB.fencingToken(), "two-node-old-token", 4, 2, Map.of("text", "Y")).accepted()).isFalse();
        assertThat(submitAs(fixture, NODE_B, sameNodeToken2.fencingToken(), "two-node-c", 5, 2,
                Map.of("anchorAtomId", atomId("two-node-b", 0), "text", "C")).accepted()).isTrue();

        assertThat(resumeWindowService.decide(fixture.roomId(), fixture.editorId(), 0).operations()).hasSize(3);
        assertInvariantPass(fixture);
        assertRoomSeqSafe(fixture);
    }
}
