package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.syncforge.api.documentstate.application.DocumentStateService;
import com.syncforge.api.operation.store.OperationRepository;
import com.syncforge.api.snapshot.api.SnapshotReplayResponse;
import com.syncforge.api.snapshot.application.SnapshotReplayService;
import com.syncforge.api.snapshot.application.SnapshotService;
import com.syncforge.api.snapshot.model.DocumentSnapshot;
import com.syncforge.api.snapshot.store.SnapshotRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SnapshotBoundaryIntegrationTest extends TextConvergenceTestSupport {
    @Autowired
    SnapshotService snapshotService;

    @Autowired
    SnapshotReplayService snapshotReplayService;

    @Autowired
    SnapshotRepository snapshotRepository;

    @Autowired
    OperationRepository operationRepository;

    @Autowired
    DocumentStateService documentStateService;

    @Test
    void snapshotStoresVisibleTextAtRoomSeqAndSnapshotTailEqualsFullReplay() {
        Fixture fixture = fixture();
        submitAcceptedText(fixture, "boundary-a", 1, 0, "TEXT_INSERT_AFTER", Map.of("text", "A"));
        submitAcceptedText(fixture, "boundary-b", 2, 1, "TEXT_INSERT_AFTER",
                Map.of("anchorAtomId", "boundary-a:0", "text", "B"));
        submitAcceptedText(fixture, "boundary-delete-a", 3, 2, "TEXT_DELETE_ATOMS",
                Map.of("atomIds", List.of("boundary-a:0")));
        DocumentSnapshot snapshot = snapshotService.createSnapshot(fixture.roomId(), "MANUAL");
        submitAcceptedText(fixture, "boundary-c", 4, 3, "TEXT_INSERT_AFTER",
                Map.of("anchorAtomId", "boundary-b:0", "text", "C"));

        SnapshotReplayResponse replay = snapshotReplayService.replayFromLatestSnapshot(fixture.roomId());
        DocumentStateService.ReplayResult full = documentStateService.replayOperations(
                operationRepository.findByRoom(fixture.roomId()), "");

        assertThat(snapshot.roomSeq()).isEqualTo(3);
        assertThat(snapshot.contentText()).isEqualTo("B");
        assertThat(full.content()).isEqualTo("BC");
        assertThat(replay.replayEquivalent()).isTrue();
    }

    @Test
    void newerSnapshotSupersedesOlderAndStaleSnapshotCannotBecomeLatest() {
        Fixture fixture = fixture();
        submitAcceptedText(fixture, "boundary-newer-a", 1, 0, "TEXT_INSERT_AFTER", Map.of("text", "A"));
        DocumentSnapshot older = snapshotService.createSnapshot(fixture.roomId(), "MANUAL");
        submitAcceptedText(fixture, "boundary-newer-b", 2, 1, "TEXT_INSERT_AFTER",
                Map.of("anchorAtomId", "boundary-newer-a:0", "text", "B"));
        DocumentSnapshot newer = snapshotService.createSnapshot(fixture.roomId(), "MANUAL");

        snapshotRepository.create(fixture.roomId(), fixture.documentId(), older.roomSeq(), older.revision(),
                older.contentText(), older.contentChecksum(), "REBUILD");

        assertThat(snapshotService.getLatestSnapshot(fixture.roomId()).id()).isEqualTo(newer.id());
        assertThat(newer.roomSeq()).isGreaterThan(older.roomSeq());
    }
}
