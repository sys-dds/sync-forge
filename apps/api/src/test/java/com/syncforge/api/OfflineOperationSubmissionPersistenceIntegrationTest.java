package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.syncforge.api.operation.model.OfflineOperationSubmission;
import com.syncforge.api.operation.model.OfflineOperationSubmissionStatus;
import com.syncforge.api.operation.store.OfflineOperationSubmissionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class OfflineOperationSubmissionPersistenceIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    OfflineOperationSubmissionRepository repository;

    @Test
    void acceptedAndRejectedOfflineOutcomesPersistCanonicalHashAndDependencies() {
        Fixture fixture = fixture();

        OfflineOperationSubmission accepted = repository.recordAccepted(
                fixture.roomId(),
                fixture.editorId(),
                "offline-client",
                "client-op-accepted",
                0,
                0,
                "hash-accepted",
                List.of("dep-op-1"),
                "accepted-operation-id",
                1);

        assertThat(accepted.status()).isEqualTo(OfflineOperationSubmissionStatus.ACCEPTED);
        assertThat(accepted.canonicalPayloadHash()).isEqualTo("hash-accepted");
        assertThat(accepted.causalDependencies()).containsExactly("dep-op-1");
        assertThat(accepted.acceptedOperationId()).isEqualTo("accepted-operation-id");
        assertThat(accepted.acceptedRoomSeq()).isEqualTo(1);

        OfflineOperationSubmission rejected = repository.recordRejected(
                fixture.roomId(),
                fixture.editorId(),
                "offline-client",
                "client-op-rejected",
                0,
                0,
                "hash-rejected",
                List.of(),
                "OFFLINE_CONFLICT",
                "offline operation requires resync");

        assertThat(rejected.status()).isEqualTo(OfflineOperationSubmissionStatus.REJECTED);
        assertThat(rejected.canonicalPayloadHash()).isEqualTo("hash-rejected");
        assertThat(rejected.rejectionCode()).isEqualTo("OFFLINE_CONFLICT");
        assertThat(rejected.rejectionReason()).isEqualTo("offline operation requires resync");
        assertThat(repository.countByRoom(fixture.roomId())).isEqualTo(2);
    }

    @Test
    void roomUserClientOperationIdIsUniqueAndExistingOutcomeWins() {
        Fixture fixture = fixture();

        OfflineOperationSubmission first = repository.recordAccepted(
                fixture.roomId(),
                fixture.editorId(),
                "offline-client",
                "client-op-unique",
                0,
                0,
                "hash-original",
                List.of(),
                "operation-original",
                1);
        OfflineOperationSubmission duplicate = repository.recordRejected(
                fixture.roomId(),
                fixture.editorId(),
                "offline-client",
                "client-op-unique",
                0,
                0,
                "hash-different",
                List.of(),
                "DIFFERENT_PAYLOAD",
                "duplicate changed payload");

        assertThat(duplicate.id()).isEqualTo(first.id());
        assertThat(duplicate.status()).isEqualTo(OfflineOperationSubmissionStatus.ACCEPTED);
        assertThat(duplicate.canonicalPayloadHash()).isEqualTo("hash-original");
        assertThat(repository.countByRoom(fixture.roomId())).isEqualTo(1);
    }
}
