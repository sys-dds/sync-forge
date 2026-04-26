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

class OfflineCausalDependencyIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    OperationService operationService;

    @Autowired
    CanonicalOperationPayloadHasher payloadHasher;

    @Autowired
    OperationRepository operationRepository;

    @Autowired
    RoomEventOutboxRepository outboxRepository;

    @Test
    void satisfiedRoomSeqAndOperationIdDependenciesAllowSubmit() {
        Fixture fixture = fixture();
        OperationSubmitResult dependency = submitOnline(fixture, "dependency-op", 1, 0,
                Map.of("position", 0, "text", "a"));

        OperationSubmitResult accepted = submitOffline(fixture, "offline-satisfied-dependency", 2, 1, 1,
                1L, List.of("dependency-op"), Map.of("position", 1, "text", "b"));

        assertThat(dependency.accepted()).isTrue();
        assertThat(accepted.accepted()).isTrue();
        assertThat(operationRepository.findByRoom(fixture.roomId())).hasSize(2);
        assertThat(outboxRepository.findByRoomSeq(fixture.roomId(), accepted.roomSeq())).isPresent();
    }

    @Test
    void missingRoomSeqOrOperationIdDependencyRejectsBeforeMutation() {
        Fixture fixture = fixture();

        OperationSubmitResult missingSeq = submitOffline(fixture, "offline-missing-seq", 1, 0, 0,
                99L, List.of(), Map.of("position", 0, "text", "x"));
        OperationSubmitResult missingOperation = submitOffline(fixture, "offline-missing-operation", 2, 0, 0,
                null, List.of("missing-op"), Map.of("position", 0, "text", "x"));

        assertThat(missingSeq.code()).isEqualTo("CAUSAL_DEPENDENCY_MISSING");
        assertThat(missingOperation.code()).isEqualTo("CAUSAL_DEPENDENCY_MISSING");
        assertThat(operationRepository.findByRoom(fixture.roomId())).isEmpty();
        assertThat(outboxRepository.findByRoomSeq(fixture.roomId(), 1)).isEmpty();
        assertThat(getMap("/api/v1/rooms/" + fixture.roomId() + "/sequence"))
                .containsEntry("currentRoomSeq", 0)
                .containsEntry("currentRevision", 0);
    }

    @Test
    void crossRoomDependencyRejectedWithoutLeakingPayloadDetails() {
        Fixture fixture = fixture();
        Fixture other = fixture();
        submitOnline(other, "cross-room-dependency", 1, 0, Map.of("position", 0, "text", "secret"));

        OperationSubmitResult rejected = submitOffline(fixture, "offline-cross-room-dependency", 1, 0, 0,
                null, List.of("cross-room-dependency"), Map.of("position", 0, "text", "x"));

        assertThat(rejected.accepted()).isFalse();
        assertThat(rejected.code()).isEqualTo("CAUSAL_DEPENDENCY_CROSS_ROOM");
        assertThat(rejected.message()).doesNotContain("secret").doesNotContain(other.roomId().toString());
        assertThat(operationRepository.findByRoom(fixture.roomId())).isEmpty();
        assertThat(outboxRepository.findByRoomSeq(fixture.roomId(), 1)).isEmpty();
    }

    private OperationSubmitResult submitOnline(
            Fixture fixture,
            String operationId,
            long clientSeq,
            long baseRevision,
            Map<String, Object> operation) {
        return operationService.submit(new SubmitOperationCommand(
                fixture.roomId(),
                fixture.editorId(),
                "offline-causal-connection",
                "offline-causal-session",
                operationId,
                clientSeq,
                baseRevision,
                "TEXT_INSERT",
                operation));
    }

    private OperationSubmitResult submitOffline(
            Fixture fixture,
            String operationId,
            long clientSeq,
            long baseRevision,
            long baseRoomSeq,
            Long dependsOnRoomSeq,
            List<String> dependsOnOperationIds,
            Map<String, Object> operation) {
        return operationService.submit(new SubmitOperationCommand(
                fixture.roomId(),
                fixture.editorId(),
                "offline-causal-connection",
                "offline-causal-session",
                operationId,
                clientSeq,
                baseRevision,
                "TEXT_INSERT",
                operation,
                true,
                operationId + "-client",
                baseRoomSeq,
                dependsOnRoomSeq,
                dependsOnOperationIds,
                payloadHasher.hash("TEXT_INSERT", operation)));
    }
}
