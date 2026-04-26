package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import com.syncforge.api.documentstate.application.DocumentStateService;
import com.syncforge.api.documentstate.model.DocumentLiveState;
import com.syncforge.api.harness.DeterministicCollaborationHarness;
import com.syncforge.api.operation.application.OperationService;
import com.syncforge.api.operation.model.OperationSubmitResult;
import com.syncforge.api.operation.model.SubmitOperationCommand;
import com.syncforge.api.shared.BadRequestException;
import com.syncforge.api.snapshot.api.SnapshotReplayResponse;
import com.syncforge.api.snapshot.application.SnapshotReplayService;
import com.syncforge.api.snapshot.application.SnapshotService;
import com.syncforge.api.snapshot.model.DocumentSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SnapshotEquivalenceIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    OperationService operationService;

    @Autowired
    DocumentStateService documentStateService;

    @Autowired
    SnapshotService snapshotService;

    @Autowired
    SnapshotReplayService snapshotReplayService;

    @Test
    void snapshotReplayEquivalenceCasesHoldAndCorruptionFailsExplicitly() {
        DeterministicCollaborationHarness.Fixture fixture = harness().createFixture(39001);

        submit(fixture, "owner", "no-snapshot-a", 1, 0, "TEXT_INSERT", Map.of("position", 0, "text", "a"));
        assertThat(documentStateService.verifyFullReplayEquivalence(fixture.roomId())).isTrue();

        DocumentSnapshot one = snapshotService.createSnapshot(fixture.roomId(), "MANUAL");
        submit(fixture, "editor", "tail-b", 1, 1, "TEXT_INSERT", Map.of("position", 1, "text", "b"));
        SnapshotReplayResponse oneReplay = snapshotReplayService.replayFromLatestSnapshot(fixture.roomId());
        DocumentLiveState liveAfterOne = documentStateService.getOrInitialize(fixture.roomId());
        assertEquivalent(oneReplay, liveAfterOne);
        assertThat(oneReplay.operationsReplayed()).isEqualTo(1);

        DocumentSnapshot two = snapshotService.createSnapshot(fixture.roomId(), "PERIODIC");
        OperationSubmitResult transformed = submit(fixture, "owner", "zzzz-snapshot-transform-c", 2, 1, "TEXT_INSERT",
                Map.of("position", 1, "text", "c"));
        assertThat(transformed.transformed()).isTrue();
        DocumentSnapshot three = snapshotService.createSnapshot(fixture.roomId(), "REBUILD");
        assertThat(snapshotService.getLatestSnapshot(fixture.roomId()).id()).isEqualTo(three.id());
        assertThat(snapshotService.getLatestSnapshot(fixture.roomId()).revision()).isGreaterThan(two.revision());
        assertThat(two.revision()).isGreaterThan(one.revision());

        SnapshotReplayResponse first = snapshotReplayService.replayFromLatestSnapshot(fixture.roomId());
        SnapshotReplayResponse second = snapshotReplayService.replayFromLatestSnapshot(fixture.roomId());
        DocumentLiveState live = documentStateService.getOrInitialize(fixture.roomId());
        assertEquivalent(first, live);
        assertEquivalent(second, live);
        assertThat(first.resultingChecksum()).isEqualTo(second.resultingChecksum());
        assertThat(documentStateService.verifyFullReplayEquivalence(fixture.roomId())).isTrue();

        jdbcTemplate.update("update document_snapshots set content_checksum = 'corrupt' where id = ?",
                three.id());
        assertThatThrownBy(() -> snapshotReplayService.replayFromLatestSnapshot(fixture.roomId()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Snapshot checksum does not match snapshot content");
    }

    private void assertEquivalent(SnapshotReplayResponse replay, DocumentLiveState live) {
        assertThat(replay.resultingRoomSeq()).isEqualTo(live.currentRoomSeq());
        assertThat(replay.resultingRevision()).isEqualTo(live.currentRevision());
        assertThat(replay.resultingChecksum()).isEqualTo(live.contentChecksum());
        assertThat(replay.replayEquivalent()).isTrue();
    }

    private OperationSubmitResult submit(
            DeterministicCollaborationHarness.Fixture fixture,
            String clientKey,
            String operationId,
            long clientSeq,
            long baseRevision,
            String operationType,
            Map<String, Object> operation) {
        return operationService.submit(new SubmitOperationCommand(
                fixture.roomId(),
                fixture.clients().get(clientKey).userId(),
                clientKey + "-connection",
                clientKey + "-session",
                operationId,
                clientSeq,
                baseRevision,
                operationType,
                operation));
    }

    private DeterministicCollaborationHarness harness() {
        return new DeterministicCollaborationHarness(restTemplate, baseUrl, jdbcTemplate, operationService, documentStateService);
    }
}
