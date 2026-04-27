package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

class StaleOwnerRejectionIntegrationTest extends RoomOwnershipTestSupport {
    @Test
    void staleOwnerCannotAppendAdvanceRoomSeqMutateStateOrCreateOutbox() {
        Fixture fixture = fixture();
        var first = acquire(fixture, NODE_A);
        var takeover = takeover(fixture, NODE_B, 1);
        var beforeOutbox = outboxCount(fixture.roomId());

        var stale = submitAs(fixture, NODE_A, first.fencingToken(), "stale-write", 1, 0, Map.of("text", "X"));
        var accepted = submitAs(fixture, NODE_B, takeover.fencingToken(), "fresh-write", 2, 0, Map.of("text", "B"));

        assertThat(stale.accepted()).isFalse();
        assertThat(stale.code()).isEqualTo("FENCING_TOKEN_REJECTED");
        assertThat(accepted.accepted()).isTrue();
        assertThat(operationCount(fixture.roomId())).isEqualTo(1);
        assertThat(outboxCount(fixture.roomId())).isEqualTo(beforeOutbox + 1);
        assertThat(documentStateService.getOrInitialize(fixture.roomId()).contentText()).isEqualTo("B");
        assertThat(operationRepository.maxRoomSeq(fixture.roomId())).isEqualTo(1);
    }
}
