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
import com.syncforge.api.resume.application.RoomBackfillService;
import com.syncforge.api.resume.model.BackfillResult;
import com.syncforge.api.text.store.TextConvergenceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class OfflineTextConvergenceIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    OperationService operationService;

    @Autowired
    DocumentStateService documentStateService;

    @Autowired
    RoomBackfillService backfillService;

    @Autowired
    TextConvergenceRepository textRepository;

    @Autowired
    CanonicalOperationPayloadHasher payloadHasher;

    @Test
    void offlineAcceptedInsertAndDeleteConvergeWithVisibleText() {
        Fixture fixture = fixture();
        submitOnline(fixture, "offline-text-anchor", 1, 0, "TEXT_INSERT_AFTER", Map.of("text", "A"));

        OperationSubmitResult insert = submitOffline(fixture, "offline-text-b", "client-text-b", 2, 1, 1,
                "TEXT_INSERT_AFTER", Map.of("anchorAtomId", "offline-text-anchor:0", "text", "B"));
        OperationSubmitResult delete = submitOffline(fixture, "offline-text-delete-a", "client-delete-a", 3, 2, 2,
                "TEXT_DELETE_ATOMS", Map.of("atomIds", List.of("offline-text-anchor:0")));

        assertThat(insert.accepted()).isTrue();
        assertThat(delete.accepted()).isTrue();
        assertThat(documentStateService.getOrInitialize(fixture.roomId()).contentText()).isEqualTo("B");
        assertThat(textRepository.findAtom(fixture.roomId(), "offline-text-anchor:0").orElseThrow().tombstoned()).isTrue();
    }

    @Test
    void duplicateOfflineRetryDoesNotDuplicateTextAndChangedPayloadConflicts() {
        Fixture fixture = fixture();
        Map<String, Object> operation = Map.of("text", "A");

        OperationSubmitResult first = submitOffline(fixture, "offline-idem-a", "client-idem-a", 1, 0, 0,
                "TEXT_INSERT_AFTER", operation);
        OperationSubmitResult retry = submitOffline(fixture, "offline-idem-a-retry", "client-idem-a", 2, 0, 0,
                "TEXT_INSERT_AFTER", operation);
        OperationSubmitResult conflict = submitOffline(fixture, "offline-idem-a-conflict", "client-idem-a", 3, 0, 0,
                "TEXT_INSERT_AFTER", Map.of("text", "B"));

        assertThat(first.accepted()).isTrue();
        assertThat(retry.accepted()).isTrue();
        assertThat(retry.duplicate()).isTrue();
        assertThat(conflict.accepted()).isFalse();
        assertThat(conflict.code()).isEqualTo("OFFLINE_CLIENT_OPERATION_CONFLICT");
        assertThat(documentStateService.getOrInitialize(fixture.roomId()).contentText()).isEqualTo("A");
        assertThat(textRepository.countRoomAtoms(fixture.roomId())).isEqualTo(1);
    }

    @Test
    void staleUnsafeOfflineTextOperationRejectsSafely() {
        Fixture fixture = fixture();

        OperationSubmitResult rejected = submitOffline(fixture, "offline-unsafe-anchor", "client-unsafe-anchor",
                1, 0, 0, "TEXT_INSERT_AFTER", Map.of("anchorAtomId", "missing:0", "text", "bad"));

        assertThat(rejected.accepted()).isFalse();
        assertThat(rejected.code()).isEqualTo("INVALID_OPERATION_PAYLOAD");
        assertThat(documentStateService.getOrInitialize(fixture.roomId()).contentText()).isEmpty();
        assertThat(textRepository.countRoomAtoms(fixture.roomId())).isZero();
        assertThat(outboxCount(fixture.roomId())).isZero();
    }

    @Test
    void permissionDeniedOfflineTextOperationCreatesNoMutationOrOutbox() {
        Fixture fixture = fixture();
        jdbcTemplate.update("""
                update room_memberships
                set status = 'REMOVED'
                where room_id = ? and user_id = ?
                """, fixture.roomId(), fixture.editorId());

        OperationSubmitResult denied = submitOffline(fixture, "offline-denied", "client-denied", 1, 0, 0,
                "TEXT_INSERT_AFTER", Map.of("text", "A"));

        assertThat(denied.accepted()).isFalse();
        assertThat(denied.code()).isEqualTo("EDIT_PERMISSION_REQUIRED");
        assertThat(documentStateService.getOrInitialize(fixture.roomId()).contentText()).isEmpty();
        assertThat(textRepository.countRoomAtoms(fixture.roomId())).isZero();
        assertThat(outboxCount(fixture.roomId())).isZero();
    }

    @Test
    void reconnectBackfillStillReturnsOfflineTextOperationsFromDbTruth() {
        Fixture fixture = fixture();
        submitOnline(fixture, "backfill-anchor", 1, 0, "TEXT_INSERT_AFTER", Map.of("text", "A"));
        submitOffline(fixture, "backfill-offline-b", "client-backfill-b", 2, 1, 1,
                "TEXT_INSERT_AFTER", Map.of("anchorAtomId", "backfill-anchor:0", "text", "B"));

        BackfillResult result = backfillService.backfill(fixture.roomId(), fixture.editorId(), "client-session", 0);

        assertThat(result.outcome()).isEqualTo("BACKFILLED");
        assertThat(result.events()).hasSize(2);
        assertThat(result.events()).extracting(event -> event.get("operationType"))
                .containsExactly("TEXT_INSERT_AFTER", "TEXT_INSERT_AFTER");
        assertThat(documentStateService.getOrInitialize(fixture.roomId()).contentText()).isEqualTo("AB");
    }

    private OperationSubmitResult submitOnline(Fixture fixture, String operationId, long clientSeq, long baseRevision,
            String operationType, Map<String, Object> operation) {
        return operationService.submit(command(fixture, operationId, clientSeq, baseRevision, operationType, operation,
                false, null, null));
    }

    private OperationSubmitResult submitOffline(Fixture fixture, String operationId, String clientOperationId,
            long clientSeq, long baseRevision, long baseRoomSeq, String operationType, Map<String, Object> operation) {
        return operationService.submit(command(fixture, operationId, clientSeq, baseRevision, operationType, operation,
                true, clientOperationId, baseRoomSeq));
    }

    private SubmitOperationCommand command(Fixture fixture, String operationId, long clientSeq, long baseRevision,
            String operationType, Map<String, Object> operation, boolean offline, String clientOperationId, Long baseRoomSeq) {
        return new SubmitOperationCommand(
                fixture.roomId(),
                fixture.editorId(),
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
                offline ? payloadHasher.hash(operationType, operation) : null);
    }

    private long outboxCount(UUID roomId) {
        Long count = jdbcTemplate.queryForObject("select count(*) from room_event_outbox where room_id = ?",
                Long.class, roomId);
        return count == null ? 0 : count;
    }
}
