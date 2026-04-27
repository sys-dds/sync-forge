package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import com.syncforge.api.documentstate.application.DocumentStateService;
import com.syncforge.api.operation.store.OperationRepository;
import com.syncforge.api.resume.application.ResumeWindowService;
import com.syncforge.api.snapshot.api.SnapshotReplayResponse;
import com.syncforge.api.snapshot.application.SnapshotReplayService;
import com.syncforge.api.snapshot.application.SnapshotService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class BoundedReplayIntegrationTest extends TextConvergenceTestSupport {
    @Autowired
    SnapshotService snapshotService;

    @Autowired
    SnapshotReplayService snapshotReplayService;

    @Autowired
    OperationRepository operationRepository;

    @Autowired
    DocumentStateService documentStateService;

    @Autowired
    ResumeWindowService resumeWindowService;

    @Test
    void boundedReplayFromSnapshotAppliesOnlyTailAndEqualsFullReplay() {
        Fixture fixture = fixture();
        submitAcceptedText(fixture, "bounded-a", 1, 0, "TEXT_INSERT_AFTER", Map.of("text", "A"));
        submitAcceptedText(fixture, "bounded-b", 2, 1, "TEXT_INSERT_AFTER",
                Map.of("anchorAtomId", "bounded-a:0", "text", "B"));
        snapshotService.createSnapshot(fixture.roomId(), "MANUAL");
        submitAcceptedText(fixture, "bounded-delete-a", 3, 2, "TEXT_DELETE_ATOMS",
                Map.of("atomIds", List.of("bounded-a:0")));
        submitAcceptedText(fixture, "bounded-c", 4, 3, "TEXT_INSERT_AFTER",
                Map.of("anchorAtomId", "bounded-b:0", "text", "C"));

        SnapshotReplayResponse replay = snapshotReplayService.replayFromLatestSnapshot(fixture.roomId());
        DocumentStateService.ReplayResult full = documentStateService.replayOperations(
                operationRepository.findByRoom(fixture.roomId()), "");

        assertThat(replay.operationsReplayed()).isEqualTo(2);
        assertThat(full.content()).isEqualTo("BC");
        assertThat(documentStateService.getOrInitialize(fixture.roomId()).contentText()).isEqualTo(full.content());
    }

    @Test
    void boundedReplayHandlesEmptyTailAndInvalidResumeRange() {
        Fixture fixture = fixture();
        submitAcceptedText(fixture, "bounded-empty-a", 1, 0, "TEXT_INSERT_AFTER", Map.of("text", "A"));
        snapshotService.createSnapshot(fixture.roomId(), "MANUAL");

        SnapshotReplayResponse replay = snapshotReplayService.replayFromLatestSnapshot(fixture.roomId());

        assertThat(replay.operationsReplayed()).isZero();
        assertThat(documentStateService.getOrInitialize(fixture.roomId()).contentText()).isEqualTo("A");
        assertThatThrownBy(() -> resumeWindowService.decide(fixture.roomId(), fixture.editorId(), 2))
                .hasMessageContaining("fromRoomSeq is ahead");
    }
}
