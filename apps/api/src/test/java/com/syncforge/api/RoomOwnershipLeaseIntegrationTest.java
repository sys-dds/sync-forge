package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RoomOwnershipLeaseIntegrationTest extends RoomOwnershipTestSupport {
    @Test
    void firstAcquireRenewBlockOtherNodeAndTakeoverAfterExpiry() {
        Fixture fixture = fixture();

        var first = acquire(fixture, NODE_A);
        var renewed = ownershipService.acquireOrRenew(fixture.roomId(), NODE_A);
        assertThat(renewed.fencingToken()).isEqualTo(first.fencingToken());
        assertThat(renewed.leaseExpiresAt()).isAfterOrEqualTo(first.leaseExpiresAt());

        assertThatThrownBy(() -> ownershipService.acquireOrRenew(fixture.roomId(), NODE_B))
                .hasMessageContaining("active for another node");

        var takeover = takeover(fixture, NODE_B, 1);
        assertThat(takeover.ownerNodeId()).isEqualTo(NODE_B);
        assertThat(takeover.fencingToken()).isEqualTo(first.fencingToken() + 1);
        assertThat(ownershipRepository.events(fixture.roomId()))
                .extracting("eventType")
                .contains("ACQUIRED", "RENEWED", "TAKEOVER");
    }
}
