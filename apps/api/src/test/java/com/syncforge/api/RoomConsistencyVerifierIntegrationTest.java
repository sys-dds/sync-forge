package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RoomConsistencyVerifierIntegrationTest extends RuntimeControlTestSupport {
    @Test
    void healthyTextRoomPassesAndGuidedDriftFails() {
        Fixture fixture = fixture();
        insert(fixture, "verify-a", 1, 0, "START", "A");
        snapshot(fixture);

        assertThat(consistencyVerifier.verify(fixture.roomId()).status().name()).isEqualTo("PASS");

        corruptDocumentState(fixture, "drift");

        var snapshot = consistencyVerifier.verify(fixture.roomId());
        assertThat(snapshot.status().name()).isEqualTo("FAIL");
        assertThat(snapshot.violations()).extracting("code").contains("TEXT_MATERIALIZATION_MISMATCH");
    }
}
