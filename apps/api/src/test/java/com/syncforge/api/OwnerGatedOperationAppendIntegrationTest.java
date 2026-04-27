package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

class OwnerGatedOperationAppendIntegrationTest extends RoomOwnershipTestSupport {
    @Test
    void appendAcquiresOwnershipStoresMetadataAndKeepsRetriesSafe() {
        Fixture fixture = fixture();
        var result = submitAcceptedText(fixture, "owner-append-a", 1, 0, "TEXT_INSERT_AFTER", Map.of("text", "A"));
        var operation = operationRepository.findByRoomSeq(fixture.roomId(), result.roomSeq()).orElseThrow();
        var duplicate = submitText(fixture, "owner-append-a", 2, 0, "TEXT_INSERT_AFTER", Map.of("text", "A"));

        assertThat(operation.ownerNodeId()).isEqualTo("test-node-1");
        assertThat(operation.fencingToken()).isEqualTo(1);
        assertThat(duplicate.duplicate()).isTrue();
        assertThat(operationCount(fixture.roomId())).isEqualTo(1);
    }
}
