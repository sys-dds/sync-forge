package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.syncforge.api.delivery.RoomEventOutboxRepository;
import com.syncforge.api.documentstate.application.DocumentStateService;
import com.syncforge.api.operation.application.CanonicalOperationPayloadHasher;
import com.syncforge.api.operation.application.OperationService;
import com.syncforge.api.operation.model.OperationSubmitResult;
import com.syncforge.api.operation.model.SubmitOperationCommand;
import com.syncforge.api.operation.store.OperationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class OfflineBaseRevisionValidationIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    OperationService operationService;

    @Autowired
    CanonicalOperationPayloadHasher payloadHasher;

    @Autowired
    OperationRepository operationRepository;

    @Autowired
    RoomEventOutboxRepository outboxRepository;

    @Autowired
    DocumentStateService documentStateService;

    @Test
    void baseRoomSeqAndBaseRevisionMustNotBeNegativeOrAheadOfServer() {
        Fixture fixture = fixture();

        OperationSubmitResult negativeSeq = submitOffline(fixture, "offline-negative-seq", 1, 0, -1,
                "TEXT_INSERT", Map.of("position", 0, "text", "x"));
        OperationSubmitResult aheadRevision = submitOffline(fixture, "offline-ahead-revision", 2, 99, 0,
                "TEXT_INSERT", Map.of("position", 0, "text", "x"));
        OperationSubmitResult aheadRoomSeq = submitOffline(fixture, "offline-ahead-room-seq", 3, 0, 99,
                "TEXT_INSERT", Map.of("position", 0, "text", "x"));

        assertThat(negativeSeq.code()).isEqualTo("INVALID_BASE_ROOM_SEQ");
        assertThat(aheadRevision.code()).isEqualTo("STALE_BASE_REVISION");
        assertThat(aheadRoomSeq.code()).isEqualTo("BASE_ROOM_SEQ_AHEAD");
        assertThat(operationRepository.findByRoom(fixture.roomId())).isEmpty();
        assertThat(outboxRepository.findByRoomSeq(fixture.roomId(), 1)).isEmpty();
        assertThat(getMap("/api/v1/rooms/" + fixture.roomId() + "/sequence"))
                .containsEntry("currentRoomSeq", 0)
                .containsEntry("currentRevision", 0);
    }

    @Test
    void staleButCompatibleOfflineBaseTransformsAndCommits() {
        Fixture fixture = fixture();
        OperationSubmitResult online = submitOnline(fixture, "aaa-online", 1, 0,
                "TEXT_INSERT", Map.of("position", 0, "text", "a"));

        OperationSubmitResult offline = submitOffline(fixture, "zzz-offline-compatible", 2, 0, 0,
                "TEXT_INSERT", Map.of("position", 0, "text", "b"));

        assertThat(online.accepted()).isTrue();
        assertThat(offline.accepted()).isTrue();
        assertThat(offline.roomSeq()).isEqualTo(2);
        assertThat(offline.revision()).isEqualTo(2);
        assertThat(offline.transformed()).isTrue();
        assertThat(documentStateService.getOrInitialize(fixture.roomId()).contentText()).isEqualTo("ab");
        assertThat(outboxRepository.findByRoomSeq(fixture.roomId(), 2)).isPresent();
    }

    @Test
    void staleIncompatibleOfflineBaseRequiresResyncWithoutMutationOutboxOrSequenceAdvance() {
        Fixture fixture = fixture();
        OperationSubmitResult online = submitOnline(fixture, "replace-online", 1, 0,
                "TEXT_INSERT", Map.of("position", 0, "text", "a"));

        OperationSubmitResult rejected = submitOffline(fixture, "replace-offline-stale", 2, 0, 0,
                "TEXT_REPLACE", Map.of("position", 0, "length", 1, "text", "z"));

        assertThat(online.accepted()).isTrue();
        assertThat(rejected.accepted()).isFalse();
        assertThat(rejected.code()).isEqualTo("CONFLICT_REQUIRES_RESYNC");
        assertThat(operationRepository.findByRoom(fixture.roomId())).hasSize(1);
        assertThat(outboxRepository.findByRoomSeq(fixture.roomId(), 2)).isEmpty();
        assertThat(documentStateService.getOrInitialize(fixture.roomId()).contentText()).isEqualTo("a");
        assertThat(getMap("/api/v1/rooms/" + fixture.roomId() + "/sequence"))
                .containsEntry("currentRoomSeq", 1)
                .containsEntry("currentRevision", 1);
    }

    private OperationSubmitResult submitOnline(
            Fixture fixture,
            String operationId,
            long clientSeq,
            long baseRevision,
            String operationType,
            Map<String, Object> operation) {
        return operationService.submit(new SubmitOperationCommand(
                fixture.roomId(),
                fixture.editorId(),
                "offline-base-connection",
                "offline-base-session",
                operationId,
                clientSeq,
                baseRevision,
                operationType,
                operation));
    }

    private OperationSubmitResult submitOffline(
            Fixture fixture,
            String operationId,
            long clientSeq,
            long baseRevision,
            long baseRoomSeq,
            String operationType,
            Map<String, Object> operation) {
        return operationService.submit(new SubmitOperationCommand(
                fixture.roomId(),
                fixture.editorId(),
                "offline-base-connection",
                "offline-base-session",
                operationId,
                clientSeq,
                baseRevision,
                operationType,
                operation,
                true,
                operationId + "-client",
                baseRoomSeq,
                null,
                List.of(),
                payloadHasher.hash(operationType, operation)));
    }
}
