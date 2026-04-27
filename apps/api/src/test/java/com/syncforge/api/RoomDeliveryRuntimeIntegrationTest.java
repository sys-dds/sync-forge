package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RoomDeliveryRuntimeIntegrationTest extends RuntimeControlTestSupport {
    @Test
    void deliveryStatusIncludesRoomOutboxCounts() {
        Fixture fixture = fixture();
        insert(fixture, "delivery-a", 1, 0, "START", "A");

        var delivery = deliveryRuntimeService.status(fixture.roomId());

        assertThat(delivery.outboxPendingCount()).isEqualTo(1);
        assertThat(delivery.latestAcceptedRoomSeq()).isEqualTo(1);
        assertThat(delivery.deliveryStatus()).isEqualTo("BACKLOGGED");
    }
}
