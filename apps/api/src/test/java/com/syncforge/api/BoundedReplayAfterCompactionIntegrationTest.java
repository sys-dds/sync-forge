package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import com.syncforge.api.documentstate.application.DocumentStateService;
import com.syncforge.api.operation.application.OperationCompactionService;
import com.syncforge.api.operation.store.OperationRepository;
import com.syncforge.api.snapshot.api.SnapshotReplayResponse;
import com.syncforge.api.snapshot.application.SnapshotReplayService;
import com.syncforge.api.snapshot.application.SnapshotService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class BoundedReplayAfterCompactionIntegrationTest extends TextConvergenceTestSupport {
    @Autowired
    SnapshotService snapshotService;

    @Autowired
    SnapshotReplayService snapshotReplayService;

    @Autowired
    OperationCompactionService compactionService;

    @Autowired
    OperationRepository operationRepository;

    @Autowired
    DocumentStateService documentStateService;

    @Test
    void boundedReplayUsesOnlyActiveTailAfterSnapshotBoundary() {
        Fixture fixture = fixture();
        submitAcceptedText(fixture, "bounded-compact-a", 1, 0, "TEXT_INSERT_AFTER", Map.of("text", "A"));
        snapshotService.createSnapshot(fixture.roomId(), "MANUAL");
        submitAcceptedText(fixture, "bounded-compact-b", 2, 1, "TEXT_INSERT_AFTER",
                Map.of("anchorAtomId", "bounded-compact-a:0", "text", "B"));

        compactionService.compactSafeHistory(fixture.roomId());
        SnapshotReplayResponse replay = snapshotReplayService.replayFromLatestSnapshot(fixture.roomId());

        assertThat(operationRepository.findActiveByRoomAfterRoomSeq(fixture.roomId(), 1))
                .extracting("operationId")
                .containsExactly("bounded-compact-b");
        assertThat(operationRepository.countActiveThroughRoomSeq(fixture.roomId(), 1)).isZero();
        assertThat(replay.tailOperationsReplayed()).isEqualTo(1);
        assertThat(replay.resultingChecksum()).isEqualTo(documentStateService.checksum("AB"));
    }
}
