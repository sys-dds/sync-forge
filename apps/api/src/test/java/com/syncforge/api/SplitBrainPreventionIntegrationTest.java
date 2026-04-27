package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

class SplitBrainPreventionIntegrationTest extends RoomOwnershipTestSupport {
    @Test
    void onlyLatestFencingTokenCanWriteAndRoomSeqRemainsGapless() {
        Fixture fixture = fixture();
        var nodeA = acquire(fixture, NODE_A);
        assertThatThrownBy(() -> ownershipService.acquireOrRenew(fixture.roomId(), NODE_B))
                .hasMessageContaining("active for another node");
        var nodeB = takeover(fixture, NODE_B, 1);

        var stale = submitAs(fixture, NODE_A, nodeA.fencingToken(), "split-stale", 1, 0, Map.of("text", "S"));
        var fresh1 = submitAs(fixture, NODE_B, nodeB.fencingToken(), "split-fresh-1", 2, 0, Map.of("text", "A"));
        var fresh2 = submitAs(fixture, NODE_B, nodeB.fencingToken(), "split-fresh-2", 3, 1,
                Map.of("anchorAtomId", "split-fresh-1:0", "text", "B"));

        assertThat(stale.accepted()).isFalse();
        assertThat(fresh1.roomSeq()).isEqualTo(1);
        assertThat(fresh2.roomSeq()).isEqualTo(2);
        assertThat(operationRepository.findByRoom(fixture.roomId()))
                .extracting("roomSeq")
                .containsExactly(1L, 2L);
        assertThat(resumeWindowService.decide(fixture.roomId(), fixture.editorId(), 0).operations())
                .extracting(operation -> operation.get("operationId"))
                .containsExactly("split-fresh-1", "split-fresh-2");
    }
}
