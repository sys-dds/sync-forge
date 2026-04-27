package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class RoomStateRepairRebuildIntegrationTest extends RuntimeControlTestSupport {
    @Test
    void rebuildRestoresDriftFromCanonicalTruthAndPreservesOperationLogAndOutbox() {
        Fixture fixture = fixture();
        insert(fixture, "repair-a", 1, 0, "START", "A");
        snapshot(fixture);
        corruptDocumentState(fixture, "bad");
        runtimeControlService.pauseWrites(fixture.roomId(), fixture.ownerId(), "repair");
        runtimeControlService.markRepairRequired(fixture.roomId(), fixture.ownerId(), "drift");
        long operationsBefore = operationCount(fixture.roomId());
        long outboxBefore = outboxCount(fixture.roomId());

        var denied = restTemplate.postForEntity(baseUrl + "/api/v1/rooms/" + fixture.roomId()
                + "/runtime/repair/rebuild-state?userId=" + fixture.viewerId(), java.util.Map.of("reason", "nope"), String.class);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        var result = repairService.rebuildState(fixture.roomId(), fixture.ownerId(), "repair drift");

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(documentStateService.getOrInitialize(fixture.roomId()).contentText()).isEqualTo("A");
        assertThat(operationCount(fixture.roomId())).isEqualTo(operationsBefore);
        assertThat(outboxCount(fixture.roomId())).isEqualTo(outboxBefore);
        assertThat(runtimeControlService.state(fixture.roomId()).repairRequired()).isFalse();
        assertThat(runtimeControlService.state(fixture.roomId()).writesPaused()).isTrue();
    }
}
