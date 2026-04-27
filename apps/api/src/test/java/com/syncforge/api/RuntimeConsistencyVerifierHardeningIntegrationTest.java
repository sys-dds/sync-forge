package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RuntimeConsistencyVerifierHardeningIntegrationTest extends JepsenLiteTestSupport {
    @Test
    void verifierDetectsChecksumTextOutboxAndResumeDriftWithRecommendedActions() {
        Fixture fixture = fixture();
        insert(fixture, "verifier-a", 1, 0, "START", "A");
        snapshot(fixture);

        corruptChecksumOnly(fixture);
        assertInvariantFail(fixture, "DOCUMENT_STATE_CHECKSUM_MISMATCH");
        assertThat(consistencyVerifier.verify(fixture.roomId()).violations())
                .allMatch(violation -> violation.recommendedAction() != null);

        corruptVisibleTextOnly(fixture, "wrong-visible");
        assertInvariantFail(fixture, "TEXT_MATERIALIZATION_MISMATCH");

        documentStateService.rebuildFromOperationLog(fixture.roomId());
        deleteOutboxForRoomSeq(fixture, 1);
        assertInvariantFail(fixture, "MISSING_OUTBOX_ROW");
    }
}
