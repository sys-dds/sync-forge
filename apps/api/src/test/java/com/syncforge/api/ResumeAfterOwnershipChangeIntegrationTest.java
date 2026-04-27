package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

class ResumeAfterOwnershipChangeIntegrationTest extends RoomOwnershipTestSupport {
    @Test
    void resumeAndBackfillUseCanonicalDbTruthAfterOwnershipTakeover() {
        Fixture fixture = fixture();
        var nodeA = acquire(fixture, NODE_A);
        submitAs(fixture, NODE_A, nodeA.fencingToken(), "resume-owner-a", 1, 0, Map.of("text", "A"));
        var nodeB = takeover(fixture, NODE_B, 1);
        submitAs(fixture, NODE_B, nodeB.fencingToken(), "resume-owner-b", 2, 1,
                Map.of("anchorAtomId", "resume-owner-a:0", "text", "B"));

        assertThat(resumeWindowService.decide(fixture.roomId(), fixture.editorId(), 0).operations())
                .extracting(operation -> operation.get("operationId"))
                .containsExactly("resume-owner-a", "resume-owner-b");
        assertThat(documentStateService.getOrInitialize(fixture.roomId()).contentText()).isEqualTo("AB");
    }
}
