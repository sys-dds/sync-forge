package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.syncforge.api.documentstate.application.DocumentStateService;
import com.syncforge.api.operation.application.OperationCompactionService;
import com.syncforge.api.snapshot.application.SnapshotReplayService;
import com.syncforge.api.snapshot.application.SnapshotService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class TombstoneCompactionSafetyIntegrationTest extends TextConvergenceTestSupport {
    @Autowired
    SnapshotService snapshotService;

    @Autowired
    SnapshotReplayService snapshotReplayService;

    @Autowired
    OperationCompactionService compactionService;

    @Autowired
    DocumentStateService documentStateService;

    @Test
    void tombstonedTextDoesNotReappearAfterCompactionAndReplay() {
        Fixture fixture = fixture();
        submitAcceptedText(fixture, "tombstone-compact-a", 1, 0, "TEXT_INSERT_AFTER", Map.of("text", "A"));
        submitAcceptedText(fixture, "tombstone-compact-b", 2, 1, "TEXT_INSERT_AFTER",
                Map.of("anchorAtomId", "tombstone-compact-a:0", "text", "B"));
        submitAcceptedText(fixture, "tombstone-compact-delete-a", 3, 2, "TEXT_DELETE_ATOMS",
                Map.of("atomIds", List.of("tombstone-compact-a:0")));
        snapshotService.createSnapshot(fixture.roomId(), "MANUAL");

        compactionService.compactSafeHistory(fixture.roomId());
        snapshotReplayService.replayFromLatestSnapshot(fixture.roomId());

        assertThat(documentStateService.getOrInitialize(fixture.roomId()).contentText()).isEqualTo("B");
        assertThat(documentStateService.getOrInitialize(fixture.roomId()).contentText()).doesNotContain("A");
    }
}
