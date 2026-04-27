package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class RoomInvariantSnapshotApiIntegrationTest extends RuntimeControlTestSupport {
    @Test
    void invariantApiReturnsViolationDataAndDeniesOutsider() {
        Fixture fixture = fixture();
        insert(fixture, "invariant-a", 1, 0, "START", "A");
        corruptDocumentState(fixture, "broken");

        var body = runtimeGet(fixture, "/invariants");
        assertThat(body).containsEntry("status", "FAIL");
        assertThat((Integer) body.get("violationCount")).isGreaterThan(0);

        var denied = restTemplate.getForEntity(baseUrl + "/api/v1/rooms/" + fixture.roomId()
                + "/runtime/invariants?userId=" + fixture.outsiderId(), String.class);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
