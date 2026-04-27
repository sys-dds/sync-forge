package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class LongRunningRoomSimulationIntegrationTest extends RuntimeControlTestSupport {
    @Test
    void deterministicLongRunningRoomSimulationMaintainsFinalInvariants() {
        Fixture fixture = fixture();
        long revision = 0;
        List<String> timeline = new ArrayList<>();
        try {
            for (int i = 0; i < 30; i++) {
                String operationId = "long-" + i;
                var result = insert(fixture, operationId, i + 1, revision, i == 0 ? "START" : atomId("long-" + (i - 1), 0),
                        String.valueOf((char) ('a' + (i % 26))));
                revision = result.revision();
                timeline.add(operationId);
                if (i == 10) {
                    snapshot(fixture);
                }
                if (i == 15) {
                    delete(fixture, "long-del", 100, revision, atomId("long-0", 0));
                    revision++;
                    compactionService.compactSafeHistory(fixture.roomId());
                }
                if (i == 29) {
                    var lease = ownershipService.currentOwnership(fixture.roomId());
                    takeover(fixture, NODE_B, 1);
                    assertThat(submitAs(fixture, lease.ownerNodeId(), lease.fencingToken(), "long-stale", 101, revision,
                            Map.of("text", "x")).accepted()).isFalse();
                }
            }
            runtimeControlService.pauseWrites(fixture.roomId(), fixture.ownerId(), "simulation");
            runtimeControlService.forceResync(fixture.roomId(), fixture.ownerId(), "simulation");
            deliveryRuntimeService.drain(fixture.roomId());
            runtimeControlService.resumeWrites(fixture.roomId(), fixture.ownerId(), "simulation");

            assertThat(consistencyVerifier.verify(fixture.roomId()).status().name()).isEqualTo("PASS");
            assertThat(documentStateService.verifyFullReplayEquivalence(fixture.roomId())).isTrue();
            assertThat(operationRepository.countDistinctRoomSeq(fixture.roomId())).isEqualTo(operationCount(fixture.roomId()));
            assertThat(runtimeHealthService.health(fixture.roomId()).status().name()).isIn("HEALTHY", "RESYNC_REQUIRED", "DEGRADED");
        } catch (AssertionError error) {
            throw new AssertionError("seed=15015 timeline=" + timeline, error);
        }
    }
}
