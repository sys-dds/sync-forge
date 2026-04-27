package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

class RoomFailoverIntegrationTest extends RoomOwnershipTestSupport {
    @Test
    void nodeATimesOutNodeBTakesOverAndOldOwnerIsBlocked() {
        Fixture fixture = fixture();
        var nodeA = acquire(fixture, NODE_A);
        var nodeB = takeover(fixture, NODE_B, 1);

        var oldOwner = submitAs(fixture, NODE_A, nodeA.fencingToken(), "failover-old", 1, 0, Map.of("text", "old"));
        var newOwner = submitAs(fixture, NODE_B, nodeB.fencingToken(), "failover-new", 2, 0, Map.of("text", "new"));

        assertThat(oldOwner.accepted()).isFalse();
        assertThat(newOwner.accepted()).isTrue();
        assertThat(documentStateService.getOrInitialize(fixture.roomId()).contentText()).isEqualTo("new");
    }
}
