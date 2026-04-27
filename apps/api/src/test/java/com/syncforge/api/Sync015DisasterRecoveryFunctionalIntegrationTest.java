package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

class Sync015DisasterRecoveryFunctionalIntegrationTest extends RuntimeControlTestSupport {
    @Test
    void disasterRecoveryFlowPreservesCanonicalStateAndBlocksRemovedUsers() {
        Fixture fixture = fixture();
        insert(fixture, "disaster-a", 1, 0, "START", "A");
        insert(fixture, "disaster-b", 2, 1, atomId("disaster-a", 0), "B");
        snapshot(fixture);
        delete(fixture, "disaster-c", 3, 2, atomId("disaster-a", 0));
        insert(fixture, "disaster-d", 4, 3, atomId("disaster-b", 0), "C");

        var ownerLease = ownershipService.currentOwnership(fixture.roomId());
        var newOwner = takeover(fixture, NODE_B, 1);
        var stale = submitAs(fixture, ownerLease.ownerNodeId(), ownerLease.fencingToken(), "disaster-stale", 5, 4,
                Map.of("text", "X"));
        assertThat(stale.accepted()).isFalse();
        assertThat(stale.code()).isEqualTo("FENCING_TOKEN_REJECTED");
        assertThat(submitAs(fixture, NODE_B, newOwner.fencingToken(), "disaster-e", 6, 4,
                Map.of("anchorAtomId", atomId("disaster-d", 0), "text", "D")).accepted()).isTrue();

        jdbcTemplate.update("update room_memberships set status = 'REMOVED' where room_id = ? and user_id = ?",
                fixture.roomId(), fixture.viewerId());
        runtimeControlService.pauseWrites(fixture.roomId(), fixture.ownerId(), "disaster pause");
        runtimeControlService.forceResync(fixture.roomId(), fixture.ownerId(), "disaster resync");
        repairService.rebuildState(fixture.roomId(), fixture.ownerId(), "disaster rebuild");
        deliveryRuntimeService.drain(fixture.roomId());
        runtimeControlService.resumeWrites(fixture.roomId(), fixture.ownerId(), "recovered");

        assertThat(resumeWindowService.decide(fixture.roomId(), fixture.editorId(), 4).decision().name()).isEqualTo("RESUMABLE");
        assertThat(restTemplate.getForEntity(baseUrl + "/api/v1/rooms/" + fixture.roomId()
                + "/resume?userId=" + fixture.viewerId() + "&fromRoomSeq=0", String.class).getStatusCode().value()).isEqualTo(403);
        assertThat(documentStateService.getOrInitialize(fixture.roomId()).contentText()).isEqualTo("BCD");
        assertThat(consistencyVerifier.verify(fixture.roomId()).status().name()).isEqualTo("PASS");
        assertThat(operationRepository.countDistinctRoomSeq(fixture.roomId())).isEqualTo(operationCount(fixture.roomId()));
        assertThat(operationRepository.countDistinctOperationIds(fixture.roomId())).isEqualTo(operationCount(fixture.roomId()));
    }
}
