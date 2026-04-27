package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OwnershipFencingAuditIntegrationTest extends RuntimeControlTestSupport {
    @Test
    void normalOwnershipHistoryPassesAndStaleRejectionIsAuditable() {
        Fixture fixture = fixture();
        var a = acquire(fixture, NODE_A);
        takeover(fixture, NODE_B, 1);
        ownershipService.recordFencedWriteRejected(fixture.roomId(), NODE_A, a.fencingToken());

        var audit = ownershipFencingAuditService.audit(fixture.roomId());

        assertThat(audit.status().name()).isEqualTo("PASS");
        assertThat(ownershipRepository.events(fixture.roomId()))
                .extracting("eventType")
                .contains("FENCED_WRITE_REJECTED", "TAKEOVER");
    }
}
