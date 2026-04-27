package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import com.syncforge.api.runtime.RoomRuntimeStatus;
import org.junit.jupiter.api.Test;

class RoomRuntimeHealthIntegrationTest extends RuntimeControlTestSupport {
    @Test
    void ownerSeesRuntimeHealthWithCanonicalRuntimeFields() {
        Fixture fixture = fixture();
        insert(fixture, "health-a", 1, 0, "START", "A");
        snapshot(fixture);

        var health = runtimeHealthService.health(fixture.roomId());

        assertThat(health.status()).isEqualTo(RoomRuntimeStatus.HEALTHY);
        assertThat(health.latestRoomSeq()).isEqualTo(1);
        assertThat(health.operationCount()).isEqualTo(1);
        assertThat(health.snapshotRoomSeq()).isEqualTo(1);
        assertThat(health.currentOwnerNodeId()).isNotBlank();
        assertThat(runtimeGet(fixture, "/health")).containsEntry("status", "HEALTHY");
    }

    @Test
    void pausedAndRepairRequiredAreReflectedInHealth() {
        Fixture fixture = fixture();
        runtimePost(fixture, "/pause", "TEST_PAUSE");
        assertThat(runtimeHealthService.health(fixture.roomId()).status()).isEqualTo(RoomRuntimeStatus.PAUSED);

        poisonOperationService.quarantine(fixture.roomId(), "poison-op", 1L, "test failure", fixture.ownerId());
        assertThat(runtimeHealthService.health(fixture.roomId()).status()).isEqualTo(RoomRuntimeStatus.REPAIR_REQUIRED);
    }
}
