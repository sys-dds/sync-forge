package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

class Sync014RoomOwnershipFunctionalIntegrationTest extends RoomOwnershipTestSupport {
    @Test
    void sync099To108OwnershipFencingFailoverFunctionalPath() {
        Fixture fixture = fixture();
        var nodeA = acquire(fixture, NODE_A);
        submitAs(fixture, NODE_A, nodeA.fencingToken(), "sync014-a", 1, 0, Map.of("text", "A"));
        var nodeB = takeover(fixture, NODE_B, 1);
        var stale = submitAs(fixture, NODE_A, nodeA.fencingToken(), "sync014-stale", 2, 1, Map.of("text", "S"));
        submitAs(fixture, NODE_B, nodeB.fencingToken(), "sync014-b", 3, 1,
                Map.of("anchorAtomId", "sync014-a:0", "text", "B"));
        snapshotService.createSnapshot(fixture.roomId(), "MANUAL");

        assertThat(stale.accepted()).isFalse();
        assertThat(snapshotReplayService.replayFromLatestSnapshot(fixture.roomId()).replayEquivalent()).isTrue();
        assertThat(operationRepository.findByRoom(fixture.roomId()))
                .extracting(operation -> operation.operationId())
                .containsExactly("sync014-a", "sync014-b");
        assertThat(documentStateService.getOrInitialize(fixture.roomId()).contentText()).isEqualTo("AB");
    }
}
