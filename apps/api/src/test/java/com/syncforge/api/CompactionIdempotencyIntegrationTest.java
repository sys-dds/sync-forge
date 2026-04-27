package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.syncforge.api.documentstate.application.DocumentStateService;
import com.syncforge.api.operation.application.OperationCompactionService;
import com.syncforge.api.operation.model.OperationSubmitResult;
import com.syncforge.api.snapshot.application.SnapshotService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CompactionIdempotencyIntegrationTest extends TextConvergenceTestSupport {
    @Autowired
    SnapshotService snapshotService;

    @Autowired
    OperationCompactionService compactionService;

    @Autowired
    DocumentStateService documentStateService;

    @Test
    void duplicateOnlineAndOfflineRetriesRemainSafeAfterCompaction() {
        Fixture fixture = fixture();
        OperationSubmitResult online = submitAcceptedText(fixture, "idempotent-compact-a", 1, 0,
                "TEXT_INSERT_AFTER", Map.of("text", "A"));
        OperationSubmitResult offline = submitOfflineText(fixture, "idempotent-compact-b", "client-op-b", 2, 1, 1,
                "TEXT_INSERT_AFTER", Map.of("anchorAtomId", "idempotent-compact-a:0", "text", "B"));
        assertThat(online.accepted()).isTrue();
        assertThat(offline.accepted()).isTrue();
        submitAcceptedText(fixture, "idempotent-compact-delete-a", 3, 2, "TEXT_DELETE_ATOMS",
                Map.of("atomIds", List.of("idempotent-compact-a:0")));
        long operationsBefore = operationCount(fixture.roomId());
        long outboxBefore = outboxCount(fixture.roomId());
        snapshotService.createSnapshot(fixture.roomId(), "MANUAL");
        compactionService.compactSafeHistory(fixture.roomId());

        OperationSubmitResult onlineRetry = submitText(fixture, "idempotent-compact-a", 4, 0,
                "TEXT_INSERT_AFTER", Map.of("text", "A"));
        OperationSubmitResult offlineRetry = submitOfflineText(fixture, "idempotent-compact-b", "client-op-b", 5, 1, 1,
                "TEXT_INSERT_AFTER", Map.of("anchorAtomId", "idempotent-compact-a:0", "text", "B"));
        OperationSubmitResult deleteRetry = submitText(fixture, "idempotent-compact-delete-a", 6, 2,
                "TEXT_DELETE_ATOMS", Map.of("atomIds", List.of("idempotent-compact-a:0")));
        OperationSubmitResult changedPayload = submitText(fixture, "idempotent-compact-a", 7, 0,
                "TEXT_INSERT_AFTER", Map.of("text", "X"));

        assertThat(onlineRetry.duplicate()).isTrue();
        assertThat(offlineRetry.duplicate()).isTrue();
        assertThat(deleteRetry.duplicate()).isTrue();
        assertThat(changedPayload.accepted()).isFalse();
        assertThat(changedPayload.code()).isEqualTo("DUPLICATE_OPERATION_CONFLICT");
        assertThat(operationCount(fixture.roomId())).isEqualTo(operationsBefore);
        assertThat(outboxCount(fixture.roomId())).isEqualTo(outboxBefore);
        assertThat(documentStateService.getOrInitialize(fixture.roomId()).contentText()).isEqualTo("B");
    }
}
