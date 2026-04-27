package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.syncforge.api.documentstate.application.DocumentStateService;
import com.syncforge.api.operation.application.OperationCompactionService;
import com.syncforge.api.resume.application.ResumeWindowService;
import com.syncforge.api.snapshot.application.SnapshotReplayService;
import com.syncforge.api.snapshot.application.SnapshotService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class Sync013BRuntimeResumeCompactionIntegrationTest extends TextConvergenceTestSupport {
    @Autowired
    SnapshotService snapshotService;

    @Autowired
    SnapshotReplayService snapshotReplayService;

    @Autowired
    OperationCompactionService compactionService;

    @Autowired
    ResumeWindowService resumeWindowService;

    @Autowired
    DocumentStateService documentStateService;

    @Test
    void runtimeResumeSnapshotRefreshCompactionAndReplayStayEquivalent() {
        Fixture fixture = fixture();
        submitAcceptedText(fixture, "sync013b-a", 1, 0, "TEXT_INSERT_AFTER", Map.of("text", "A"));
        submitAcceptedText(fixture, "sync013b-b", 2, 1, "TEXT_INSERT_AFTER",
                Map.of("anchorAtomId", "sync013b-a:0", "text", "B"));
        submitAcceptedText(fixture, "sync013b-delete-a", 3, 2, "TEXT_DELETE_ATOMS",
                Map.of("atomIds", List.of("sync013b-a:0")));
        snapshotService.createSnapshot(fixture.roomId(), "MANUAL");
        submitAcceptedText(fixture, "sync013b-c", 4, 3, "TEXT_INSERT_AFTER",
                Map.of("anchorAtomId", "sync013b-b:0", "text", "C"));

        assertThat(compactionService.preview(fixture.roomId()).safeToCompact()).isTrue();
        assertThat(compactionService.compactSafeHistory(fixture.roomId()).compactedCount()).isEqualTo(3);
        assertThat(resumeWindowService.decide(fixture.roomId(), fixture.editorId(), 3).returnedOperationCount()).isEqualTo(1);
        assertThat(snapshotReplayService.replayFromLatestSnapshot(fixture.roomId()).replayEquivalent()).isTrue();

        Map<String, Object> refresh = getMap("/api/v1/rooms/" + fixture.roomId()
                + "/resume/snapshot-refresh?userId=" + fixture.editorId());
        assertThat(refresh).containsEntry("baselineRoomSeq", 4)
                .containsEntry("visibleText", "BC")
                .containsEntry("contentChecksum", documentStateService.checksum("BC"));
    }
}
