package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import com.syncforge.api.runtime.RecommendedRuntimeAction;
import org.junit.jupiter.api.Test;

class Sync016FinalNightmareRoomFailureMatrixIntegrationTest extends JepsenLiteTestSupport {
    @Test
    void finalNightmareRoomPreservesAllCanonicalInvariants() {
        Fixture fixture = fixture();
        insert(fixture, "night-a", 1, 0, "START", "A");
        insert(fixture, "night-b", 2, 1, atomId("night-a", 0), "B");
        insert(fixture, "night-offline", 3, 2, atomId("night-b", 0), "C");
        assertThat(submitText(fixture, "night-offline", 3, 2, "TEXT_INSERT_AFTER",
                Map.of("anchorAtomId", atomId("night-b", 0), "text", "C")).accepted()).isTrue();
        var nodeA = ownershipService.currentOwnership(fixture.roomId());
        snapshot(fixture);
        delete(fixture, "night-del", 4, 3, atomId("night-a", 0));
        compactionService.compactSafeHistory(fixture.roomId());

        TestFailureHarness harness = failureHarness(fixture);
        harness.failNextOutboxPublish();
        harness.drainWithInjectedPublishFailure();
        harness.emitDuplicateStreamEventWithoutStateChange();

        var nodeB = takeover(fixture, NODE_B, 1);
        assertThat(submitAs(fixture, nodeA.ownerNodeId(), nodeA.fencingToken(), "night-stale", 5, 4, Map.of("text", "X")).accepted()).isFalse();
        ownershipService.recordFencedPublishRejected(fixture.roomId(), nodeA.ownerNodeId(), nodeA.fencingToken());
        jdbcTemplate.update("update room_memberships set status = 'REMOVED' where room_id = ? and user_id = ?",
                fixture.roomId(), fixture.viewerId());
        assertForbiddenGet("/api/v1/rooms/" + fixture.roomId() + "/runtime?userId=" + fixture.viewerId());

        runtimeControlService.pauseWrites(fixture.roomId(), fixture.ownerId(), "night pause");
        runtimeControlService.forceResync(fixture.roomId(), fixture.ownerId(), "night resync");
        corruptDocumentState(fixture, "drift");
        assertInvariantFail(fixture, "TEXT_MATERIALIZATION_MISMATCH");
        repairService.rebuildState(fixture.roomId(), fixture.ownerId(), "night repair");
        deliveryRuntimeService.drain(fixture.roomId());
        runtimeControlService.resumeWrites(fixture.roomId(), fixture.ownerId(), "night recovered");
        assertThat(submitAs(fixture, NODE_B, nodeB.fencingToken(), "night-d", 6, 4,
                Map.of("anchorAtomId", atomId("night-offline", 0), "text", "D")).accepted()).isTrue();

        assertThat(resumeWindowService.decide(fixture.roomId(), fixture.editorId(), 4).decision().name()).isEqualTo("RESUMABLE");
        assertThat(documentStateService.getOrInitialize(fixture.roomId()).contentText()).isEqualTo("BCD");
        assertThat(snapshotReplayService.replayFromLatestSnapshot(fixture.roomId()).replayEquivalent()).isTrue();
        assertRoomSeqSafe(fixture);
        assertInvariantPass(fixture);
        assertThat(runtimeOverviewService.overview(fixture.roomId()).recommendedAction()).isIn(
                RecommendedRuntimeAction.NONE,
                RecommendedRuntimeAction.DRAIN_OUTBOX,
                RecommendedRuntimeAction.FORCE_RESYNC,
                RecommendedRuntimeAction.SNAPSHOT_REFRESH_REQUIRED);
    }
}
