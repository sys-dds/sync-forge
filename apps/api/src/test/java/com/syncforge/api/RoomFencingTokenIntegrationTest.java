package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RoomFencingTokenIntegrationTest extends RoomOwnershipTestSupport {
    @Test
    void fencingTokenIsMonotonicAndOldTokenIsRejectedAfterTakeover() {
        Fixture fixture = fixture();
        var first = acquire(fixture, NODE_A);
        var takeover = takeover(fixture, NODE_B, 1);

        assertThat(takeover.fencingToken()).isGreaterThan(first.fencingToken());
        assertThat(ownershipService.ensureCurrentOwner(fixture.roomId(), NODE_A, first.fencingToken()).accepted())
                .isFalse();
        assertThat(ownershipService.ensureCurrentOwner(fixture.roomId(), NODE_B, takeover.fencingToken()).accepted())
                .isTrue();
        assertThat(ownershipRepository.events(fixture.roomId()))
                .extracting("eventType")
                .contains("STALE_OWNER_REJECTED");
    }
}
