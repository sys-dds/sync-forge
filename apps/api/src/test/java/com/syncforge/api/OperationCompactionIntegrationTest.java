package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.syncforge.api.documentstate.application.DocumentStateService;
import com.syncforge.api.operation.application.OperationCompactionService;
import com.syncforge.api.operation.model.OperationSubmitResult;
import com.syncforge.api.operation.store.OperationRepository;
import com.syncforge.api.snapshot.application.SnapshotReplayService;
import com.syncforge.api.snapshot.application.SnapshotService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class OperationCompactionIntegrationTest extends TextConvergenceTestSupport {
    @Autowired
    OperationCompactionService compactionService;

    @Autowired
    OperationRepository operationRepository;

    @Autowired
    SnapshotService snapshotService;

    @Autowired
    SnapshotReplayService snapshotReplayService;

    @Autowired
    DocumentStateService documentStateService;

    @Test
    void compactionMarksOnlySnapshotCoveredHistoryAndPreservesReplayAndIdempotency() {
        Fixture fixture = fixture();
        submitAcceptedText(fixture, "compact-a", 1, 0, "TEXT_INSERT_AFTER", Map.of("text", "A"));
        submitAcceptedText(fixture, "compact-b", 2, 1, "TEXT_INSERT_AFTER",
                Map.of("anchorAtomId", "compact-a:0", "text", "B"));
        submitAcceptedText(fixture, "compact-delete-a", 3, 2, "TEXT_DELETE_ATOMS",
                Map.of("atomIds", List.of("compact-a:0")));
        snapshotService.createSnapshot(fixture.roomId(), "MANUAL");
        submitAcceptedText(fixture, "compact-c", 4, 3, "TEXT_INSERT_AFTER",
                Map.of("anchorAtomId", "compact-b:0", "text", "C"));

        DocumentStateService.ReplayResult before = documentStateService.replayOperations(
                operationRepository.findByRoom(fixture.roomId()), "");
        OperationCompactionService.CompactionResult first = compactionService.compactSafeHistory(fixture.roomId());
        OperationCompactionService.CompactionResult second = compactionService.compactSafeHistory(fixture.roomId());
        OperationSubmitResult duplicateInsert = submitText(fixture, "compact-b", 5, 1, "TEXT_INSERT_AFTER",
                Map.of("anchorAtomId", "compact-a:0", "text", "B"));
        OperationSubmitResult duplicateDelete = submitText(fixture, "compact-delete-a", 6, 2, "TEXT_DELETE_ATOMS",
                Map.of("atomIds", List.of("compact-a:0")));

        snapshotReplayService.replayFromLatestSnapshot(fixture.roomId());

        assertThat(first.compactedCount()).isEqualTo(3);
        assertThat(first.activeTailCount()).isEqualTo(1);
        assertThat(second.compactedCount()).isZero();
        assertThat(duplicateInsert.duplicate()).isTrue();
        assertThat(duplicateDelete.duplicate()).isTrue();
        assertThat(operationRepository.findActiveByRoomAfterRoomSeq(fixture.roomId(), 3))
                .extracting("operationId")
                .containsExactly("compact-c");
        assertThat(before.content()).isEqualTo("BC");
        assertThat(documentStateService.getOrInitialize(fixture.roomId()).contentText()).isEqualTo("BC");
    }
}
