package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LeaseRenewalExpiryIntegrationTest extends RoomOwnershipTestSupport {
    @Test
    void renewalExtendsExpiryAndExpiredLeaseCanBeMarkedExpired() {
        Fixture fixture = fixture();
        var first = acquire(fixture, NODE_A);
        var renewed = ownershipService.acquireOrRenew(fixture.roomId(), NODE_A);

        int expired = ownershipService.markExpiredLeases(renewed.leaseExpiresAt().plusSeconds(1));

        assertThat(renewed.leaseExpiresAt()).isAfterOrEqualTo(first.leaseExpiresAt());
        assertThat(expired).isEqualTo(1);
        assertThat(ownershipService.currentOwnership(fixture.roomId()).leaseStatus().name()).isEqualTo("EXPIRED");
    }
}
