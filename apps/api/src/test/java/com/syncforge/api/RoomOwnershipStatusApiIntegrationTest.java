package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class RoomOwnershipStatusApiIntegrationTest extends RoomOwnershipTestSupport {
    @Test
    void ownerCanViewStatusAndUnauthorizedUsersAreDenied() {
        Fixture fixture = fixture();
        var nodeA = acquire(fixture, NODE_A);
        ownershipService.acquireOrRenew(fixture.roomId(), NODE_A);
        var nodeB = takeover(fixture, NODE_B, 1);

        var status = getMap("/api/v1/rooms/" + fixture.roomId() + "/ownership?userId=" + fixture.ownerId());
        ResponseEntity<java.util.Map> outsider = restTemplate.getForEntity(baseUrl + "/api/v1/rooms/"
                + fixture.roomId() + "/ownership?userId=" + fixture.outsiderId(), java.util.Map.class);

        assertThat(status).containsEntry("roomId", fixture.roomId().toString())
                .containsEntry("ownerNodeId", NODE_B)
                .containsEntry("fencingToken", (int) nodeB.fencingToken())
                .containsEntry("leaseStatus", "ACTIVE")
                .containsKey("leaseExpiresAt")
                .containsKey("acquiredAt")
                .containsKey("renewedAt")
                .containsKey("latestOwnershipEvent")
                .containsEntry("isExpired", false)
                .containsKey("serverNow");
        assertThat(nodeA.ownerNodeId()).isEqualTo(NODE_A);
        assertThat(outsider.getStatusCode().is4xxClientError()).isTrue();
    }
}
