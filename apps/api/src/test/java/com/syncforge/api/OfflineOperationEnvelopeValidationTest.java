package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.syncforge.api.delivery.RoomEventOutboxRepository;
import com.syncforge.api.operation.application.CanonicalOperationPayloadHasher;
import com.syncforge.api.operation.application.OperationService;
import com.syncforge.api.operation.model.OperationSubmitResult;
import com.syncforge.api.operation.model.SubmitOperationCommand;
import com.syncforge.api.operation.store.OperationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class OfflineOperationEnvelopeValidationTest extends AbstractIntegrationTest {
    @Autowired
    OperationService operationService;

    @Autowired
    OperationRepository operationRepository;

    @Autowired
    RoomEventOutboxRepository outboxRepository;

    @Autowired
    CanonicalOperationPayloadHasher payloadHasher;

    @Test
    void existingOnlineSubmitRemainsCompatibleWithoutOfflineFields() {
        Fixture fixture = fixture();

        OperationSubmitResult result = operationService.submit(new SubmitOperationCommand(
                fixture.roomId(),
                fixture.editorId(),
                "online-compatible-connection",
                "online-compatible-session",
                "online-compatible-op",
                1L,
                0L,
                "TEXT_INSERT",
                Map.of("position", 0, "text", "online")));

        assertThat(result.accepted()).isTrue();
        assertThat(operationRepository.findByRoomAndOperationId(fixture.roomId(), "online-compatible-op")).isPresent();
        assertThat(outboxRepository.findByRoomSeq(fixture.roomId(), result.roomSeq())).isPresent();
    }

    @Test
    void offlineSubmitRequiresClientOperationIdBaseRevisionBaseRoomSeqAndCanonicalHash() {
        Fixture fixture = fixture();

        assertThat(submitOffline(fixture, "offline-missing-client-op", null, 0L, 0L, null, "hash").code())
                .isEqualTo("INVALID_CLIENT_OPERATION_ID");
        assertThat(submitOffline(fixture, "offline-missing-base-revision", "client-op-1", null, 0L, null, "hash").code())
                .isEqualTo("INVALID_BASE_REVISION");
        assertThat(submitOffline(fixture, "offline-missing-base-room-seq", "client-op-2", 0L, null, null, "hash").code())
                .isEqualTo("INVALID_BASE_ROOM_SEQ");
        assertThat(submitOffline(fixture, "offline-missing-hash", "client-op-3", 0L, 0L, null, null).code())
                .isEqualTo("INVALID_CANONICAL_PAYLOAD_HASH");

        assertThat(operationRepository.findByRoom(fixture.roomId())).isEmpty();
        assertThat(outboxRepository.findByRoomSeq(fixture.roomId(), 1)).isEmpty();
    }

    @Test
    void offlineEnvelopeRepresentsDependenciesAndRejectsBlankDependencyIdsDeterministically() {
        Fixture fixture = fixture();

        OperationSubmitResult rejected = operationService.submit(new SubmitOperationCommand(
                fixture.roomId(),
                fixture.editorId(),
                "offline-dependency-connection",
                "offline-dependency-session",
                "offline-blank-dependency",
                1L,
                0L,
                "TEXT_INSERT",
                Map.of("position", 0, "text", "x"),
                true,
                "client-op-dependency",
                0L,
                1L,
                List.of(""),
                hash("TEXT_INSERT", Map.of("position", 0, "text", "x"))));

        assertThat(rejected.accepted()).isFalse();
        assertThat(rejected.code()).isEqualTo("INVALID_CAUSAL_DEPENDENCY");

        OperationSubmitResult accepted = operationService.submit(new SubmitOperationCommand(
                fixture.roomId(),
                fixture.editorId(),
                "offline-dependency-connection",
                "offline-dependency-session",
                "offline-valid-envelope",
                2L,
                0L,
                "TEXT_INSERT",
                Map.of("position", 0, "text", "x"),
                true,
                "client-op-valid",
                0L,
                null,
                List.of(),
                hash("TEXT_INSERT", Map.of("position", 0, "text", "x"))));

        assertThat(accepted.accepted()).isTrue();
        assertThat(operationRepository.findByRoomAndOperationId(fixture.roomId(), "offline-valid-envelope")).isPresent();
    }

    private OperationSubmitResult submitOffline(
            Fixture fixture,
            String operationId,
            String clientOperationId,
            Long baseRevision,
            Long baseRoomSeq,
            Long dependsOnRoomSeq,
            String canonicalPayloadHash) {
        return operationService.submit(new SubmitOperationCommand(
                fixture.roomId(),
                fixture.editorId(),
                "offline-validation-connection",
                "offline-validation-session",
                operationId,
                1L,
                baseRevision,
                "TEXT_INSERT",
                Map.of("position", 0, "text", "x"),
                true,
                clientOperationId,
                baseRoomSeq,
                dependsOnRoomSeq,
                List.of(),
                canonicalPayloadHash));
    }

    private String hash(String operationType, Map<String, Object> operation) {
        return payloadHasher.hash(operationType, operation);
    }
}
