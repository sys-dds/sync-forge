package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class RoomRuntimeOverviewIntegrationTest extends RuntimeControlTestSupport {
    @Test
    void runtimeOverviewAggregatesStatusAndRecommendedAction() {
        Fixture fixture = fixture();
        insert(fixture, "overview-a", 1, 0, "START", "A");
        snapshot(fixture);

        var healthy = runtimeOverviewService.overview(fixture.roomId());
        assertThat(healthy.recommendedAction().name()).isIn("NONE", "DRAIN_OUTBOX");

        corruptDocumentState(fixture, "drift");
        var repair = runtimeOverviewService.overview(fixture.roomId());
        assertThat(repair.recommendedAction().name()).isEqualTo("PAUSE_AND_REPAIR");

        var denied = restTemplate.getForEntity(baseUrl + "/api/v1/rooms/" + fixture.roomId()
                + "/runtime?userId=" + fixture.editorId(), String.class);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
