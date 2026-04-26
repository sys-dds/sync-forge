package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.syncforge.api.documentstate.application.DocumentStateService;
import com.syncforge.api.operation.application.OperationService;
import com.syncforge.api.operation.model.SubmitOperationCommand;
import com.syncforge.api.operation.store.OperationRepository;
import com.syncforge.api.snapshot.api.SnapshotReplayResponse;
import com.syncforge.api.snapshot.application.SnapshotReplayService;
import com.syncforge.api.snapshot.application.SnapshotService;
import com.syncforge.api.snapshot.model.DocumentSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class TextSnapshotEquivalenceIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    OperationService operationService;

    @Autowired
    OperationRepository operationRepository;

    @Autowired
    DocumentStateService documentStateService;

    @Autowired
    SnapshotService snapshotService;

    @Autowired
    SnapshotReplayService snapshotReplayService;

    @Test
    void snapshotVisibleTextAndTailReplayEqualFullReplayAndLiveWithTombstones() {
        Fixture fixture = fixture();
        submit(fixture, "snapshot-a", 1, 0, "TEXT_INSERT_AFTER", Map.of("text", "A"));
        submit(fixture, "snapshot-b", 2, 1, "TEXT_INSERT_AFTER", Map.of("anchorAtomId", "snapshot-a:0", "text", "B"));
        submit(fixture, "snapshot-delete-a", 3, 2, "TEXT_DELETE_ATOMS", Map.of("atomIds", List.of("snapshot-a:0")));
        DocumentSnapshot snapshot = snapshotService.createSnapshot(fixture.roomId(), "MANUAL");
        submit(fixture, "snapshot-c", 4, 3, "TEXT_INSERT_AFTER", Map.of("anchorAtomId", "snapshot-b:0", "text", "C"));

        DocumentStateService.ReplayResult fullReplay = documentStateService.replayOperations(
                operationRepository.findByRoom(fixture.roomId()), "");
        SnapshotReplayResponse snapshotReplay = snapshotReplayService.replayFromLatestSnapshot(fixture.roomId());

        assertThat(snapshot.contentText()).isEqualTo("B");
        assertThat(fullReplay.content()).isEqualTo("BC");
        assertThat(snapshotReplay.checksumVerified()).isTrue();
        assertThat(snapshotReplay.replayEquivalent()).isTrue();
        assertThat(documentStateService.getOrInitialize(fixture.roomId()).contentText()).isEqualTo("BC");
        assertThat(documentStateService.verifyFullReplayEquivalence(fixture.roomId())).isTrue();
    }

    private void submit(Fixture fixture, String operationId, long clientSeq, long baseRevision, String operationType,
            Map<String, Object> operation) {
        assertThat(operationService.submit(new SubmitOperationCommand(
                fixture.roomId(),
                fixture.editorId(),
                "connection-" + operationId,
                "session-" + operationId,
                operationId,
                clientSeq,
                baseRevision,
                operationType,
                operation)).accepted()).isTrue();
    }
}
