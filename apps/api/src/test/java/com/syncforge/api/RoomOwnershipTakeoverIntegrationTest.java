package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RoomOwnershipTakeoverIntegrationTest extends RoomOwnershipTestSupport {
    @Test
    void takeoverBeforeExpiryRejectedReleaseAllowsSafeAcquire() {
        Fixture fixture = fixture();
        var first = acquire(fixture, NODE_A);

        assertThatThrownBy(() -> ownershipService.takeoverExpired(fixture.roomId(), NODE_B, "EARLY"))
                .hasMessageContaining("has not expired");
        ownershipService.release(fixture.roomId(), NODE_A, first.fencingToken(), "TEST_RELEASE");
        var takeover = ownershipService.takeoverExpired(fixture.roomId(), NODE_B, "AFTER_RELEASE");

        assertThat(takeover.ownerNodeId()).isEqualTo(NODE_B);
        assertThat(takeover.fencingToken()).isEqualTo(first.fencingToken() + 1);
        assertThat(ownershipRepository.events(fixture.roomId()))
                .extracting("eventType")
                .contains("RELEASED", "TAKEOVER", "STALE_OWNER_REJECTED");
    }
}
