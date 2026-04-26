package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.syncforge.api.documentstate.application.DocumentStateService;
import com.syncforge.api.operation.application.CanonicalOperationPayloadHasher;
import com.syncforge.api.operation.application.OperationService;
import com.syncforge.api.operation.model.OperationSubmitResult;
import com.syncforge.api.operation.model.SubmitOperationCommand;
import com.syncforge.api.operation.store.OperationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class TextReplayDeterminismIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    OperationService operationService;

    @Autowired
    OperationRepository operationRepository;

    @Autowired
    DocumentStateService documentStateService;

    @Autowired
    CanonicalOperationPayloadHasher payloadHasher;

    @Test
    void fullReplayEqualsLiveVisibleTextAndRepeatsDeterministically() {
        Fixture fixture = fixture();
        submit(fixture, "replay-a", 1, 0, "TEXT_INSERT_AFTER", Map.of("text", "A"));
        submit(fixture, "replay-b", 2, 1, "TEXT_INSERT_AFTER", Map.of("anchorAtomId", "replay-a:0", "text", "B"));
        submit(fixture, "replay-delete-a", 3, 2, "TEXT_DELETE_ATOMS", Map.of("atomIds", List.of("replay-a:0")));

        DocumentStateService.ReplayResult first = documentStateService.replayOperations(
                operationRepository.findByRoom(fixture.roomId()), "");
        DocumentStateService.ReplayResult second = documentStateService.replayOperations(
                operationRepository.findByRoom(fixture.roomId()), "");

        assertThat(first.content()).isEqualTo("B");
        assertThat(second.content()).isEqualTo(first.content());
        assertThat(first.revision()).isEqualTo(3);
        assertThat(first.roomSeq()).isEqualTo(3);
        assertThat(documentStateService.getOrInitialize(fixture.roomId()).contentText()).isEqualTo(first.content());
    }

    @Test
    void rejectedOperationsAndDuplicateRetriesDoNotChangeReplay() {
        Fixture fixture = fixture();
        submit(fixture, "replay-idem", 1, 0, "TEXT_INSERT_AFTER", Map.of("text", "A"));
        OperationSubmitResult duplicate = submit(fixture, "replay-idem", 2, 0, "TEXT_INSERT_AFTER", Map.of("text", "A"));
        OperationSubmitResult rejected = submit(fixture, "replay-missing", 3, 1, "TEXT_INSERT_AFTER",
                Map.of("anchorAtomId", "missing:0", "text", "B"));

        DocumentStateService.ReplayResult replay = documentStateService.replayOperations(
                operationRepository.findByRoom(fixture.roomId()), "");

        assertThat(duplicate.duplicate()).isTrue();
        assertThat(rejected.accepted()).isFalse();
        assertThat(replay.content()).isEqualTo("A");
        assertThat(replay.operationsReplayed()).isEqualTo(1);
    }

    @Test
    void offlineAcceptedOperationsAreIncludedInReplay() {
        Fixture fixture = fixture();
        submit(fixture, "replay-offline-a", 1, 0, "TEXT_INSERT_AFTER", Map.of("text", "A"));
        submitOffline(fixture, "replay-offline-b", "client-replay-b", 2, 1, 1,
                "TEXT_INSERT_AFTER", Map.of("anchorAtomId", "replay-offline-a:0", "text", "B"));

        DocumentStateService.ReplayResult replay = documentStateService.replayOperations(
                operationRepository.findByRoom(fixture.roomId()), "");

        assertThat(replay.content()).isEqualTo("AB");
        assertThat(documentStateService.verifyFullReplayEquivalence(fixture.roomId())).isTrue();
    }

    private OperationSubmitResult submit(Fixture fixture, String operationId, long clientSeq, long baseRevision,
            String operationType, Map<String, Object> operation) {
        return operationService.submit(new SubmitOperationCommand(
                fixture.roomId(), fixture.editorId(), "connection-" + operationId, "session-" + operationId,
                operationId, clientSeq, baseRevision, operationType, operation));
    }

    private OperationSubmitResult submitOffline(Fixture fixture, String operationId, String clientOperationId,
            long clientSeq, long baseRevision, long baseRoomSeq, String operationType, Map<String, Object> operation) {
        return operationService.submit(new SubmitOperationCommand(
                fixture.roomId(),
                fixture.editorId(),
                "connection-" + operationId,
                "session-" + operationId,
                operationId,
                clientSeq,
                baseRevision,
                operationType,
                operation,
                true,
                clientOperationId,
                baseRoomSeq,
                null,
                List.of(),
                payloadHasher.hash(operationType, operation)));
    }
}
