package com.syncforge.api;

import static com.syncforge.api.invariant.InvariantAssertions.assertAllAckedOperationsAppearInLog;
import static com.syncforge.api.invariant.InvariantAssertions.assertGaplessRoomSeq;
import static com.syncforge.api.invariant.InvariantAssertions.assertNoDuplicateAcceptedOperations;
import static com.syncforge.api.invariant.InvariantAssertions.assertReplayEqualsLive;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.syncforge.api.documentstate.application.DocumentStateService;
import com.syncforge.api.harness.DeterministicCollaborationHarness;
import com.syncforge.api.harness.ScriptedOperation;
import com.syncforge.api.operation.application.OperationService;
import com.syncforge.api.operation.model.OperationSubmitResult;
import com.syncforge.api.operation.model.SubmitOperationCommand;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

class ConcurrentEditFuzzIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    OperationService operationService;

    @Autowired
    DocumentStateService documentStateService;

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {101, 202, 303, 404, 505})
    void deterministicConcurrentStyleFuzzKeepsCoreInvariants(long seed) {
        DeterministicCollaborationHarness.Fixture fixture = harness().createFixture(seed);
        Random random = new Random(seed);
        List<String> ackedNonDuplicateIds = new ArrayList<>();
        StringBuilder expectedFromAccepted = new StringBuilder();

        for (int i = 0; i < 8; i++) {
            char letter = (char) ('a' + random.nextInt(26));
            OperationSubmitResult result = submit(fixture, insert("owner", "seed-" + seed + "-insert-" + i,
                    i + 1, expectedFromAccepted.length(), expectedFromAccepted.length(), Character.toString(letter)));
            assertThat(result.accepted()).as("seed %s insert %s", seed, i).isTrue();
            ackedNonDuplicateIds.add(result.operationId());
            expectedFromAccepted.append(letter);

            if (i == 2) {
                OperationSubmitResult duplicate = submit(fixture, insert("owner", "seed-" + seed + "-insert-" + i,
                        99, i, i, Character.toString(letter)));
                assertThat(duplicate.accepted()).as("seed %s duplicate retry", seed).isTrue();
                assertThat(duplicate.duplicate()).isTrue();
                assertThat(duplicate.roomSeq()).isEqualTo(result.roomSeq());
                assertThat(duplicate.revision()).isEqualTo(result.revision());
            }
        }

        OperationSubmitResult stale = submit(fixture, insert("editor", "seed-" + seed + "-stale",
                50, 0, 0, "z"));
        assertThat(stale.accepted()).as("seed %s stale transform", seed).isTrue();
        ackedNonDuplicateIds.add(stale.operationId());

        OperationSubmitResult noop = submit(fixture, new ScriptedOperation("owner", "seed-" + seed + "-noop",
                60, stale.revision(), "NOOP", Map.of()));
        assertThat(noop.accepted()).as("seed %s noop", seed).isTrue();
        ackedNonDuplicateIds.add(noop.operationId());

        OperationSubmitResult delete = submit(fixture, new ScriptedOperation("editor", "seed-" + seed + "-delete",
                61, noop.revision(), "TEXT_DELETE", Map.of("position", 0, "length", 1)));
        assertThat(delete.accepted()).as("seed %s delete", seed).isTrue();
        ackedNonDuplicateIds.add(delete.operationId());

        String beforeUnsafe = documentStateService.getOrInitialize(fixture.roomId()).contentText();
        OperationSubmitResult unsafe = submit(fixture, new ScriptedOperation("owner", "seed-" + seed + "-unsafe",
                62, delete.revision(), "TEXT_DELETE", Map.of("position", 9999, "length", 1)));
        assertThat(unsafe.accepted()).as("seed %s unsafe attempt", seed).isFalse();
        assertThat(documentStateService.getOrInitialize(fixture.roomId()).contentText()).isEqualTo(beforeUnsafe);

        assertReplayEqualsLive(documentStateService, fixture.roomId());
        assertNoDuplicateAcceptedOperations(jdbcTemplate, fixture.roomId());
        assertGaplessRoomSeq(jdbcTemplate, fixture.roomId());
        assertAllAckedOperationsAppearInLog(jdbcTemplate, fixture.roomId(), ackedNonDuplicateIds);
    }

    private OperationSubmitResult submit(DeterministicCollaborationHarness.Fixture fixture, ScriptedOperation operation) {
        return operationService.submit(new SubmitOperationCommand(
                fixture.roomId(),
                fixture.clients().get(operation.clientKey()).userId(),
                operation.clientKey() + "-connection",
                operation.clientKey() + "-session",
                operation.operationId(),
                operation.clientSeq(),
                operation.baseRevision(),
                operation.operationType(),
                operation.operation()));
    }

    private ScriptedOperation insert(String clientKey, String operationId, long clientSeq, long baseRevision, int position, String text) {
        return new ScriptedOperation(clientKey, operationId, clientSeq, baseRevision, "TEXT_INSERT",
                Map.of("position", position, "text", text));
    }

    private DeterministicCollaborationHarness harness() {
        return new DeterministicCollaborationHarness(restTemplate, baseUrl, jdbcTemplate, operationService, documentStateService);
    }
}
