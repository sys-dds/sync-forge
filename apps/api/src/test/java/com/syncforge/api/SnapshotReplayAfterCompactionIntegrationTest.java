package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.syncforge.api.documentstate.application.DocumentStateService;
import com.syncforge.api.operation.application.OperationCompactionService;
import com.syncforge.api.operation.store.OperationRepository;
import com.syncforge.api.resume.application.ResumeWindowService;
import com.syncforge.api.snapshot.api.SnapshotReplayResponse;
import com.syncforge.api.snapshot.application.SnapshotReplayService;
import com.syncforge.api.snapshot.application.SnapshotService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SnapshotReplayAfterCompactionIntegrationTest extends TextConvergenceTestSupport {
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

    @Autowired
    ResumeWindowService resumeWindowService;

    @Test
    void snapshotAtomsAndActiveTailRebuildVisibleTextAfterCompaction() {
        Fixture fixture = fixture();
        submitAcceptedText(fixture, "replay-compact-a", 1, 0, "TEXT_INSERT_AFTER", Map.of("text", "A"));
        submitAcceptedText(fixture, "replay-compact-b", 2, 1, "TEXT_INSERT_AFTER",
                Map.of("anchorAtomId", "replay-compact-a:0", "text", "B"));
        submitAcceptedText(fixture, "replay-compact-delete-a", 3, 2, "TEXT_DELETE_ATOMS",
                Map.of("atomIds", List.of("replay-compact-a:0")));
        snapshotService.createSnapshot(fixture.roomId(), "MANUAL");
        submitAcceptedText(fixture, "replay-compact-c", 4, 3, "TEXT_INSERT_AFTER",
                Map.of("anchorAtomId", "replay-compact-b:0", "text", "C"));
        submitAcceptedText(fixture, "replay-compact-delete-b", 5, 4, "TEXT_DELETE_ATOMS",
                Map.of("atomIds", List.of("replay-compact-b:0")));

        compactionService.compactSafeHistory(fixture.roomId());
        SnapshotReplayResponse replay = snapshotReplayService.replayFromLatestSnapshot(fixture.roomId());

        assertThat(operationRepository.countActiveThroughRoomSeq(fixture.roomId(), 3)).isZero();
        assertThat(replay.snapshotRoomSeq()).isEqualTo(3);
        assertThat(replay.tailOperationsReplayed()).isEqualTo(2);
        assertThat(replay.resultingRoomSeq()).isEqualTo(5);
        assertThat(replay.resultingChecksum()).isEqualTo(documentStateService.checksum("C"));
        assertThat(documentStateService.getOrInitialize(fixture.roomId()).contentText()).isEqualTo("C");
        assertThat(resumeWindowService.decide(fixture.roomId(), fixture.editorId(), 3).returnedOperationCount()).isEqualTo(2);
    }
}
