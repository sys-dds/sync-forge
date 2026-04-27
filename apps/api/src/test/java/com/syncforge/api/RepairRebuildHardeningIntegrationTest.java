package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RepairRebuildHardeningIntegrationTest extends JepsenLiteTestSupport {
    @Test
    void repairPreservesCanonicalTruthAndLeavesWritesPaused() {
        Fixture fixture = fixture();
        insert(fixture, "repair-a", 1, 0, "START", "A");
        snapshot(fixture);
        insert(fixture, "repair-b", 2, 1, atomId("repair-a", 0), "B");
        var lease = ownershipService.currentOwnership(fixture.roomId());
        long operations = operationCount(fixture.roomId());
        long outbox = outboxRepository.countByRoom(fixture.roomId());

        corruptDocumentState(fixture, "wrong");
        runtimeControlService.markRepairRequired(fixture.roomId(), fixture.ownerId(), "test drift");
        repairService.rebuildState(fixture.roomId(), fixture.ownerId(), "repair hardening");

        assertThat(documentStateService.getOrInitialize(fixture.roomId()).contentText()).isEqualTo("AB");
        assertThat(operationCount(fixture.roomId())).isEqualTo(operations);
        assertThat(outboxRepository.countByRoom(fixture.roomId())).isEqualTo(outbox);
        assertThat(ownershipService.currentOwnership(fixture.roomId()).fencingToken()).isEqualTo(lease.fencingToken());
        assertThat(runtimeControlService.state(fixture.roomId()).writesPaused()).isTrue();
        assertThat(runtimeControlService.state(fixture.roomId()).repairRequired()).isFalse();
    }
}
