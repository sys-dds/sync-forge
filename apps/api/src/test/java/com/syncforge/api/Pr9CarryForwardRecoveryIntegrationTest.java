package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.syncforge.api.documentstate.application.DocumentStateService;
import com.syncforge.api.operation.store.OperationRepository;
import com.syncforge.api.snapshot.api.SnapshotReplayResponse;
import com.syncforge.api.snapshot.application.SnapshotReplayService;
import com.syncforge.api.snapshot.application.SnapshotService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class Pr9CarryForwardRecoveryIntegrationTest extends TextConvergenceTestSupport {
    @Autowired
    DocumentStateService documentStateService;

    @Autowired
    OperationRepository operationRepository;

    @Autowired
    SnapshotService snapshotService;

    @Autowired
    SnapshotReplayService snapshotReplayService;

    @Test
    void snapshotReplayDoesNotReadCurrentLiveAtomsAsTailSourceOfTruth() {
        Fixture fixture = fixture();
        submitAcceptedText(fixture, "pr9-a", 1, 0, "TEXT_INSERT_AFTER", Map.of("text", "A"));
        submitAcceptedText(fixture, "pr9-b", 2, 1, "TEXT_INSERT_AFTER", Map.of("anchorAtomId", "pr9-a:0", "text", "B"));
        submitAcceptedText(fixture, "pr9-delete-a", 3, 2, "TEXT_DELETE_ATOMS", Map.of("atomIds", List.of("pr9-a:0")));
        snapshotService.createSnapshot(fixture.roomId(), "MANUAL");
        submitAcceptedText(fixture, "pr9-c", 4, 3, "TEXT_INSERT_AFTER", Map.of("anchorAtomId", "pr9-b:0", "text", "C"));

        jdbcTemplate.update("""
                update collaborative_text_atoms
                set tombstoned = true, deleted_by_operation_id = 'test-corruption', deleted_at_room_seq = 99
                where room_id = ? and atom_id = 'pr9-c:0'
                """, fixture.roomId());
        jdbcTemplate.update("""
                update document_live_states
                set content_text = 'B', content_checksum = ?
                where room_id = ?
                """, documentStateService.checksum("B"), fixture.roomId());

        SnapshotReplayResponse replay = snapshotReplayService.replayFromLatestSnapshot(fixture.roomId());
        DocumentStateService.ReplayResult fullReplay = documentStateService.replayOperations(
                operationRepository.findByRoom(fixture.roomId()), "");

        assertThat(fullReplay.content()).isEqualTo("BC");
        assertThat(replay.replayEquivalent()).isTrue();
        assertThat(documentStateService.getOrInitialize(fixture.roomId()).contentText()).isEqualTo("BC");
    }
}
