package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class RoomRuntimeControlIntegrationTest extends RuntimeControlTestSupport {
    @Test
    void ownerCanPauseResumeAndNonOwnerIsDenied() {
        Fixture fixture = fixture();
        var paused = runtimePost(fixture, "/pause", "maintenance");
        assertThat(paused).containsEntry("writesPaused", true);

        var denied = restTemplate.postForEntity(baseUrl + "/api/v1/rooms/" + fixture.roomId()
                + "/runtime/pause?userId=" + fixture.editorId(), java.util.Map.of("reason", "nope"), String.class);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        var resumed = runtimePost(fixture, "/resume-writes", "done");
        assertThat(resumed).containsEntry("writesPaused", false);
    }
}
