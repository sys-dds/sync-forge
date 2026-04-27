package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class PoisonOperationDetectionIntegrationTest extends RuntimeControlTestSupport {
    @Test
    void repeatedReplayFailureQuarantinesOperationAndRequiresRepair() {
        Fixture fixture = fixture();

        poisonOperationService.quarantine(fixture.roomId(), "bad-op", 7L, "replay failed", fixture.ownerId());
        var second = poisonOperationService.quarantine(fixture.roomId(), "bad-op", 7L, "replay failed again", fixture.ownerId());

        assertThat(second.failureCount()).isEqualTo(2);
        assertThat(poisonOperationService.listQuarantined(fixture.roomId())).hasSize(1);
        assertThat(runtimeHealthService.health(fixture.roomId()).repairRequired()).isTrue();

        var denied = restTemplate.getForEntity(baseUrl + "/api/v1/rooms/" + fixture.roomId()
                + "/runtime/poison-operations?userId=" + fixture.viewerId(), String.class);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
