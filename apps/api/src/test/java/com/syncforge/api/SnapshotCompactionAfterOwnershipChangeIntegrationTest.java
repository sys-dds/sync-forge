package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

class SnapshotCompactionAfterOwnershipChangeIntegrationTest extends RoomOwnershipTestSupport {
    @Test
    void snapshotRefreshBoundedReplayAndCompactionRemainSafeAfterTakeover() {
        Fixture fixture = fixture();
        var nodeA = acquire(fixture, NODE_A);
        submitAs(fixture, NODE_A, nodeA.fencingToken(), "snapshot-owner-a", 1, 0, Map.of("text", "A"));
        snapshotService.createSnapshot(fixture.roomId(), "MANUAL");
        var nodeB = takeover(fixture, NODE_B, 1);
        submitAs(fixture, NODE_B, nodeB.fencingToken(), "snapshot-owner-b", 2, 1,
                Map.of("anchorAtomId", "snapshot-owner-a:0", "text", "B"));

        assertThat(snapshotReplayService.replayFromLatestSnapshot(fixture.roomId()).replayEquivalent()).isTrue();
        assertThat(compactionService.preview(fixture.roomId()).safeToCompact()).isTrue();
        assertThat(compactionService.compactSafeHistory(fixture.roomId()).compactedCount()).isEqualTo(1);
        assertThat(getMap("/api/v1/rooms/" + fixture.roomId()
                + "/resume/snapshot-refresh?userId=" + fixture.editorId()))
                .containsEntry("visibleText", "AB")
                .containsEntry("baselineRoomSeq", 2);
        assertThat(operationRepository.findByRoom(fixture.roomId()))
                .extracting("roomSeq")
                .containsExactly(1L, 2L);
    }
}
