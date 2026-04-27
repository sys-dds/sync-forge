package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

class JepsenLiteStaleOwnerFencingIntegrationTest extends JepsenLiteTestSupport {
    @Test
    void staleOwnersAndSameNodeOldTokensAreFencedBeforeMutation() {
        Fixture fixture = fixture();
        var nodeA = acquire(fixture, NODE_A);
        assertThat(submitAs(fixture, NODE_A, nodeA.fencingToken(), "fence-a", 1, 0, Map.of("text", "A")).accepted()).isTrue();
        var nodeB = takeover(fixture, NODE_B, 1);

        var staleWrite = submitAs(fixture, NODE_A, nodeA.fencingToken(), "fence-stale", 2, 1, Map.of("text", "X"));
        assertThat(staleWrite.accepted()).isFalse();
        ownershipService.recordFencedPublishRejected(fixture.roomId(), NODE_A, nodeA.fencingToken());
        assertThat(submitAs(fixture, NODE_B, nodeB.fencingToken(), "fence-b", 3, 1,
                Map.of("anchorAtomId", atomId("fence-a", 0), "text", "B")).accepted()).isTrue();

        var nodeB2 = takeover(fixture, NODE_B, 1);
        assertThat(submitAs(fixture, NODE_B, nodeB.fencingToken(), "fence-old-same-node", 4, 2, Map.of("text", "Y")).accepted()).isFalse();
        assertThat(submitAs(fixture, NODE_B, nodeB2.fencingToken(), "fence-c", 5, 2,
                Map.of("anchorAtomId", atomId("fence-b", 0), "text", "C")).accepted()).isTrue();

        assertThat(ownershipFencingAuditService.audit(fixture.roomId()).status().name()).isEqualTo("PASS");
        assertThat(textConvergenceService.materializeVisibleText(fixture.roomId())).isEqualTo("ABC");
        assertRoomSeqSafe(fixture);
        assertInvariantPass(fixture);
    }
}
