package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import com.syncforge.api.documentstate.application.DocumentStateService;
import com.syncforge.api.operation.application.CanonicalOperationPayloadHasher;
import com.syncforge.api.operation.application.OperationService;
import com.syncforge.api.operation.model.OperationSubmitResult;
import com.syncforge.api.operation.model.SubmitOperationCommand;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class OfflineStaleSnapshotConflictIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    OperationService operationService;

    @Autowired
    CanonicalOperationPayloadHasher payloadHasher;

    @Autowired
    DocumentStateService documentStateService;

    @Test
    void staleClientSnapshotProducesDeterministicConflictWithoutHiddenMutation() {
        Fixture fixture = fixture();
        OperationSubmitResult seed = submitOnline(fixture, "seed-op", 1, 0,
                Map.of("position", 0, "text", "server"));

        Map<String, Object> staleReplace = Map.of("expectedText", "client", "replacementText", "offline");
        OperationSubmitResult rejected = operationService.submit(new SubmitOperationCommand(
                fixture.roomId(),
                fixture.editorId(),
                "stale-snapshot-connection",
                "stale-snapshot-session",
                "stale-replace",
                2L,
                0L,
                "TEXT_REPLACE",
                staleReplace,
                true,
                "stale-client-op",
                0L,
                null,
                java.util.List.of(),
                payloadHasher.hash("TEXT_REPLACE", staleReplace)));

        assertThat(seed.accepted()).isTrue();
        assertThat(rejected.accepted()).isFalse();
        assertThat(rejected.code()).isEqualTo("CONFLICT_REQUIRES_RESYNC");
        assertThat(rejected.currentRevision()).isEqualTo(1);
        assertThat(documentStateService.getOrInitialize(fixture.roomId()).contentText()).isEqualTo("server");
        assertThat(roomSeq(fixture)).isEqualTo(1);
        assertThat(outboxRows(fixture)).isEqualTo(1);
    }

    private OperationSubmitResult submitOnline(
            Fixture fixture,
            String operationId,
            long clientSeq,
            long baseRevision,
            Map<String, Object> operation) {
        return operationService.submit(new SubmitOperationCommand(
                fixture.roomId(),
                fixture.ownerId(),
                "stale-snapshot-owner-connection",
                "stale-snapshot-owner-session",
                operationId,
                clientSeq,
                baseRevision,
                "TEXT_INSERT",
                operation));
    }

    private long roomSeq(Fixture fixture) {
        Long max = jdbcTemplate.queryForObject(
                "select coalesce(max(room_seq), 0) from room_operations where room_id = ?",
                Long.class,
                fixture.roomId());
        return max == null ? 0L : max;
    }

    private long outboxRows(Fixture fixture) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from room_event_outbox where room_id = ?",
                Long.class,
                fixture.roomId());
        return count == null ? 0L : count;
    }
}
