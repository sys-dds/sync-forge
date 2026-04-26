package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.syncforge.api.delivery.RoomEventOutboxRepository;
import com.syncforge.api.operation.application.CanonicalOperationPayloadHasher;
import com.syncforge.api.operation.application.OperationService;
import com.syncforge.api.operation.model.OperationSubmitResult;
import com.syncforge.api.operation.model.SubmitOperationCommand;
import com.syncforge.api.operation.store.OfflineOperationSubmissionRepository;
import com.syncforge.api.operation.store.OperationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class OfflineCanonicalPayloadHashIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    CanonicalOperationPayloadHasher payloadHasher;

    @Autowired
    OperationService operationService;

    @Autowired
    OperationRepository operationRepository;

    @Autowired
    RoomEventOutboxRepository outboxRepository;

    @Autowired
    OfflineOperationSubmissionRepository offlineSubmissionRepository;

    @Test
    void canonicalHashIsStableForEquivalentPayloadsAndExcludesEnvelopeFields() {
        Map<String, Object> ordered = new LinkedHashMap<>();
        ordered.put("position", 0);
        ordered.put("text", "a");
        Map<String, Object> reordered = new LinkedHashMap<>();
        reordered.put("text", "a");
        reordered.put("position", 0);

        String first = payloadHasher.hash("TEXT_INSERT", ordered);
        String second = payloadHasher.hash("TEXT_INSERT", reordered);
        String changedPayload = payloadHasher.hash("TEXT_INSERT", Map.of("position", 0, "text", "b"));

        assertThat(first).isEqualTo(second);
        assertThat(first).isNotEqualTo(changedPayload);
        assertThat(first).isEqualTo(payloadHasher.hash("TEXT_INSERT", ordered));
    }

    @Test
    void duplicateOfflineRetryWithSameHashReturnsOriginalAckWithoutSecondMutationOrOutbox() {
        Fixture fixture = fixture();
        Map<String, Object> operation = Map.of("position", 0, "text", "a");
        String hash = payloadHasher.hash("TEXT_INSERT", operation);

        OperationSubmitResult accepted = submitOffline(fixture, "offline-hash-op", "client-hash-1", 1, operation, hash);
        OperationSubmitResult duplicate = submitOffline(fixture, "offline-hash-op-retry", "client-hash-1", 99, operation, hash);

        assertThat(accepted.accepted()).isTrue();
        assertThat(duplicate.accepted()).isTrue();
        assertThat(duplicate.duplicate()).isTrue();
        assertThat(duplicate.operationId()).isEqualTo("offline-hash-op");
        assertThat(duplicate.roomSeq()).isEqualTo(accepted.roomSeq());
        assertThat(duplicate.revision()).isEqualTo(accepted.revision());
        assertThat(operationRepository.findByRoom(fixture.roomId())).hasSize(1);
        assertThat(outboxRepository.findByRoomSeq(fixture.roomId(), accepted.roomSeq())).isPresent();
        assertThat(offlineSubmissionRepository.countByRoom(fixture.roomId())).isEqualTo(1);
    }

    @Test
    void duplicateOfflineRetryWithDifferentHashIsRejectedDeterministically() {
        Fixture fixture = fixture();
        Map<String, Object> firstPayload = Map.of("position", 0, "text", "a");
        Map<String, Object> changedPayload = Map.of("position", 0, "text", "b");

        OperationSubmitResult accepted = submitOffline(fixture, "offline-conflict-original", "client-hash-conflict", 1,
                firstPayload, payloadHasher.hash("TEXT_INSERT", firstPayload));
        OperationSubmitResult conflict = submitOffline(fixture, "offline-conflict-retry", "client-hash-conflict", 2,
                changedPayload, payloadHasher.hash("TEXT_INSERT", changedPayload));

        assertThat(accepted.accepted()).isTrue();
        assertThat(conflict.accepted()).isFalse();
        assertThat(conflict.code()).isEqualTo("OFFLINE_CLIENT_OPERATION_CONFLICT");
        assertThat(operationRepository.findByRoom(fixture.roomId())).hasSize(1);
        assertThat(outboxRepository.findByRoomSeq(fixture.roomId(), accepted.roomSeq())).isPresent();
        assertThat(outboxRepository.findByRoomSeq(fixture.roomId(), accepted.roomSeq() + 1)).isEmpty();
        assertThat(offlineSubmissionRepository.countByRoom(fixture.roomId())).isEqualTo(1);
    }

    @Test
    void serverRejectsCanonicalHashMismatchWithoutMutatingState() {
        Fixture fixture = fixture();

        OperationSubmitResult rejected = submitOffline(fixture, "offline-bad-hash", "client-bad-hash", 1,
                Map.of("position", 0, "text", "a"), "not-the-server-hash");

        assertThat(rejected.accepted()).isFalse();
        assertThat(rejected.code()).isEqualTo("INVALID_CANONICAL_PAYLOAD_HASH");
        assertThat(operationRepository.findByRoom(fixture.roomId())).isEmpty();
        assertThat(offlineSubmissionRepository.countByRoom(fixture.roomId())).isZero();
    }

    private OperationSubmitResult submitOffline(
            Fixture fixture,
            String operationId,
            String clientOperationId,
            long clientSeq,
            Map<String, Object> operation,
            String canonicalPayloadHash) {
        return operationService.submit(new SubmitOperationCommand(
                fixture.roomId(),
                fixture.editorId(),
                "offline-hash-connection",
                "offline-hash-session",
                operationId,
                clientSeq,
                0L,
                "TEXT_INSERT",
                operation,
                true,
                clientOperationId,
                0L,
                null,
                List.of(),
                canonicalPayloadHash));
    }
}
