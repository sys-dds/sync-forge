package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JepsenLiteSnapshotCompactionRepairIntegrationTest extends JepsenLiteTestSupport {
    @Test
    void snapshotAtomsActiveTailAndRepairSurviveCompactionAndTombstones() {
        Fixture fixture = fixture();
        insert(fixture, "scr-a", 1, 0, "START", "A");
        insert(fixture, "scr-b", 2, 1, atomId("scr-a", 0), "B");
        delete(fixture, "scr-del", 3, 2, atomId("scr-a", 0));
        snapshot(fixture);
        insert(fixture, "scr-c", 4, 3, atomId("scr-b", 0), "C");
        long operations = operationCount(fixture.roomId());
        long outbox = outboxRepository.countByRoom(fixture.roomId());

        compactionService.compactSafeHistory(fixture.roomId());
        corruptDocumentState(fixture, "resurrected");
        assertInvariantFail(fixture, "TEXT_MATERIALIZATION_MISMATCH");

        runtimeControlService.pauseWrites(fixture.roomId(), fixture.ownerId(), "repair matrix pause");
        repairService.rebuildState(fixture.roomId(), fixture.ownerId(), "repair matrix rebuild");

        assertThat(documentStateService.getOrInitialize(fixture.roomId()).contentText()).isEqualTo("BC");
        assertThat(documentStateService.getOrInitialize(fixture.roomId()).contentText()).doesNotContain("A");
        assertThat(operationCount(fixture.roomId())).isEqualTo(operations);
        assertThat(outboxRepository.countByRoom(fixture.roomId())).isEqualTo(outbox);
        assertThat(runtimeControlService.state(fixture.roomId()).writesPaused()).isTrue();
        assertInvariantPass(fixture);
    }
}
