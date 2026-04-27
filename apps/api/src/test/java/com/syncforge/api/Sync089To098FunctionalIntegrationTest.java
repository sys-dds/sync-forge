package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.syncforge.api.documentstate.application.DocumentStateService;
import com.syncforge.api.operation.application.OperationCompactionService;
import com.syncforge.api.operation.model.OperationSubmitResult;
import com.syncforge.api.operation.store.OperationRepository;
import com.syncforge.api.resume.application.ResumeWindowService;
import com.syncforge.api.resume.model.ResumeDecisionType;
import com.syncforge.api.snapshot.application.SnapshotReplayService;
import com.syncforge.api.snapshot.application.SnapshotService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class Sync089To098FunctionalIntegrationTest extends TextConvergenceTestSupport {
    @Autowired
    SnapshotService snapshotService;

    @Autowired
    SnapshotReplayService snapshotReplayService;

    @Autowired
    ResumeWindowService resumeWindowService;

    @Autowired
    OperationCompactionService compactionService;

    @Autowired
    OperationRepository operationRepository;

    @Autowired
    DocumentStateService documentStateService;

    @Test
    void resumeSnapshotBoundedReplayAndCompactionEndToEnd() {
        Fixture fixture = fixture();
        submitAcceptedText(fixture, "sync-089-a", 1, 0, "TEXT_INSERT_AFTER", Map.of("text", "A"));
        submitAcceptedText(fixture, "sync-089-b", 2, 1, "TEXT_INSERT_AFTER",
                Map.of("anchorAtomId", "sync-089-a:0", "text", "B"));
        OperationSubmitResult offline = submitOfflineText(fixture, "sync-089-c", "sync-089-client-c", 3, 2, 2,
                "TEXT_INSERT_AFTER", Map.of("anchorAtomId", "sync-089-b:0", "text", "C"));
        submitAcceptedText(fixture, "sync-089-delete-a", 4, 3, "TEXT_DELETE_ATOMS",
                Map.of("atomIds", List.of("sync-089-a:0")));
        snapshotService.createSnapshot(fixture.roomId(), "MANUAL");
        submitAcceptedText(fixture, "sync-089-d", 5, 4, "TEXT_INSERT_AFTER",
                Map.of("anchorAtomId", "sync-089-c:0", "text", "D"));
        OperationSubmitResult duplicate = submitOfflineText(fixture, "sync-089-c-retry", "sync-089-client-c", 6, 2, 2,
                "TEXT_INSERT_AFTER", Map.of("anchorAtomId", "sync-089-b:0", "text", "C"));
        OperationSubmitResult rejected = submitText(fixture, "sync-089-invalid", 7, 5, "TEXT_INSERT_AFTER",
                Map.of("anchorAtomId", "missing:0", "text", "X"));

        OperationCompactionService.CompactionResult compaction = compactionService.compactSafeHistory(fixture.roomId());
        snapshotReplayService.replayFromLatestSnapshot(fixture.roomId());

        assertThat(offline.accepted()).isTrue();
        assertThat(duplicate.duplicate()).isTrue();
        assertThat(rejected.accepted()).isFalse();
        assertThat(documentStateService.getOrInitialize(fixture.roomId()).contentText()).isEqualTo("BCD");
        assertThat(documentStateService.verifyFullReplayEquivalence(fixture.roomId())).isTrue();
        assertThat(resumeWindowService.decide(fixture.roomId(), fixture.editorId(), 4).decision())
                .isEqualTo(ResumeDecisionType.RESUMABLE);
        assertThat(resumeWindowService.decide(fixture.roomId(), fixture.editorId(), 0).decision())
                .isEqualTo(ResumeDecisionType.REFRESH_REQUIRED);
        assertThat(compaction.compactedCount()).isEqualTo(4);
        assertThat(operationRepository.findActiveByRoomAfterRoomSeq(fixture.roomId(), 4))
                .extracting("operationId")
                .containsExactly("sync-089-d");
        assertThat(outboxCount(fixture.roomId())).isEqualTo(5);
        assertThat(operationCount(fixture.roomId())).isEqualTo(5);
    }
}
