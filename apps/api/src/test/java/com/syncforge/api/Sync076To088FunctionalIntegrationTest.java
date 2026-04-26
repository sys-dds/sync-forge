package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.syncforge.api.documentstate.application.DocumentStateService;
import com.syncforge.api.operation.application.CanonicalOperationPayloadHasher;
import com.syncforge.api.operation.application.OperationService;
import com.syncforge.api.operation.model.OperationSubmitResult;
import com.syncforge.api.operation.model.SubmitOperationCommand;
import com.syncforge.api.operation.store.OperationRepository;
import com.syncforge.api.snapshot.application.SnapshotReplayService;
import com.syncforge.api.snapshot.application.SnapshotService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class Sync076To088FunctionalIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    OperationService operationService;

    @Autowired
    OperationRepository operationRepository;

    @Autowired
    DocumentStateService documentStateService;

    @Autowired
    SnapshotService snapshotService;

    @Autowired
    SnapshotReplayService snapshotReplayService;

    @Autowired
    CanonicalOperationPayloadHasher payloadHasher;

    @Test
    void textConvergenceEndToEndScenario() {
        Fixture fixture = fixture();
        submit(fixture, fixture.ownerId(), "sync-076-a", 1, 0, "TEXT_INSERT_AFTER", Map.of("text", "A"), false, null, null);
        submit(fixture, fixture.editorId(), "sync-076-b", 2, 1, "TEXT_INSERT_AFTER",
                Map.of("anchorAtomId", "sync-076-a:0", "text", "B"), false, null, null);
        submit(fixture, fixture.ownerId(), "sync-076-c", 3, 2, "TEXT_INSERT_AFTER",
                Map.of("anchorAtomId", "sync-076-a:0", "text", "C"), false, null, null);
        OperationSubmitResult offline = submit(fixture, fixture.editorId(), "sync-076-d", 4, 3,
                "TEXT_INSERT_AFTER", Map.of("anchorAtomId", "sync-076-a:0", "text", "D"), true, "sync-076-client-d", 3L);
        OperationSubmitResult duplicate = submit(fixture, fixture.editorId(), "sync-076-d-retry", 5, 3,
                "TEXT_INSERT_AFTER", Map.of("anchorAtomId", "sync-076-a:0", "text", "D"), true, "sync-076-client-d", 3L);
        submit(fixture, fixture.ownerId(), "sync-076-delete-b", 6, 4, "TEXT_DELETE_ATOMS",
                Map.of("atomIds", List.of("sync-076-b:0")), false, null, null);
        snapshotService.createSnapshot(fixture.roomId(), "MANUAL");
        OperationSubmitResult rejected = submit(fixture, fixture.editorId(), "sync-076-bad", 7, 5,
                "TEXT_INSERT_AFTER", Map.of("anchorAtomId", "missing:0", "text", "X"), false, null, null);

        DocumentStateService.ReplayResult replay = documentStateService.replayOperations(
                operationRepository.findByRoom(fixture.roomId()), "");
        snapshotReplayService.replayFromLatestSnapshot(fixture.roomId());

        assertThat(offline.accepted()).isTrue();
        assertThat(duplicate.duplicate()).isTrue();
        assertThat(rejected.accepted()).isFalse();
        assertThat(documentStateService.getOrInitialize(fixture.roomId()).contentText()).isEqualTo("ACD");
        assertThat(replay.content()).isEqualTo("ACD");
        assertThat(outboxCount(fixture.roomId())).isEqualTo(5);
    }

    private OperationSubmitResult submit(Fixture fixture, UUID userId, String operationId, long clientSeq, long baseRevision,
            String operationType, Map<String, Object> operation, boolean offline, String clientOperationId, Long baseRoomSeq) {
        return operationService.submit(new SubmitOperationCommand(
                fixture.roomId(),
                userId,
                "connection-" + operationId,
                "session-" + operationId,
                operationId,
                clientSeq,
                baseRevision,
                operationType,
                operation,
                offline,
                clientOperationId,
                baseRoomSeq,
                null,
                List.of(),
                offline ? payloadHasher.hash(operationType, operation) : null));
    }

    private long outboxCount(UUID roomId) {
        Long count = jdbcTemplate.queryForObject("select count(*) from room_event_outbox where room_id = ?",
                Long.class, roomId);
        return count == null ? 0 : count;
    }
}
