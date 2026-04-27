package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class RoomOutboxDrainIntegrationTest extends RuntimeControlTestSupport {
    @Test
    void ownerCanDrainRoomOutboxAndUnauthorizedUserIsDenied() {
        Fixture fixture = fixture();
        insert(fixture, "drain-a", 1, 0, "START", "A");

        var denied = restTemplate.postForEntity(baseUrl + "/api/v1/rooms/" + fixture.roomId()
                + "/runtime/delivery/drain?userId=" + fixture.viewerId(), null, String.class);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        var response = runtimePost(fixture, "/delivery/drain", "operator drain");
        assertThat((Integer) response.get("attempted")).isGreaterThanOrEqualTo(0);
        assertThat(response).containsKey("remainingPending");
    }
}
