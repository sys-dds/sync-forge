package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.syncforge.api.documentstate.application.DocumentStateService;
import com.syncforge.api.harness.CollaborationScenario;
import com.syncforge.api.harness.DeterministicCollaborationHarness;
import com.syncforge.api.harness.ScenarioResult;
import com.syncforge.api.harness.ScriptedOperation;
import com.syncforge.api.operation.application.OperationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class DeterministicCollaborationHarnessIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    OperationService operationService;

    @Autowired
    DocumentStateService documentStateService;

    @Test
    void simpleTwoEditorInsertScenarioIsDeterministic() {
        ScenarioResult result = harness().run(new CollaborationScenario(36001, List.of(
                insert("owner", "owner-insert-a", 1, 0, 0, "a"),
                insert("editor", "editor-insert-b", 1, 1, 1, "b"))));

        assertThat(result.finalContent()).isEqualTo("ab");
        assertThat(result.acceptedCount()).isEqualTo(2);
        assertThat(result.rejectedCount()).isZero();
        assertThat(result.currentRoomSeq()).isEqualTo(2);
        assertThat(result.currentRevision()).isEqualTo(2);
    }

    @Test
    void duplicateRetryReusesAcceptedResult() {
        ScenarioResult result = harness().run(new CollaborationScenario(36002, List.of(
                insert("editor", "dup-insert", 1, 0, 0, "x"),
                insert("editor", "dup-insert", 2, 0, 0, "x"))));

        assertThat(result.finalContent()).isEqualTo("x");
        assertThat(result.acceptedCount()).isEqualTo(2);
        assertThat(result.outcomes().get(1))
                .containsEntry("accepted", true)
                .containsEntry("duplicate", true)
                .containsEntry("roomSeq", 1L)
                .containsEntry("revision", 1L);
    }

    @Test
    void staleEditScenarioTransformsDeterministically() {
        ScenarioResult result = harness().run(new CollaborationScenario(36003, List.of(
                insert("owner", "stale-a", 1, 0, 0, "a"),
                insert("editor", "stale-b", 1, 0, 0, "b"))));

        assertThat(result.acceptedCount()).isEqualTo(2);
        assertThat(result.finalContent()).hasSize(2).contains("a").contains("b");
        assertThat(result.currentRoomSeq()).isEqualTo(2);
    }

    @Test
    void viewerEditIsRejectedWithoutChangingFinalState() {
        ScenarioResult result = harness().run(new CollaborationScenario(36004, List.of(
                insert("owner", "owner-visible", 1, 0, 0, "o"),
                insert("viewer", "viewer-blocked", 1, 1, 1, "v"))));

        assertThat(result.finalContent()).isEqualTo("o");
        assertThat(result.acceptedCount()).isEqualTo(1);
        assertThat(result.rejectedCount()).isEqualTo(1);
        assertThat(result.outcomes().get(1))
                .containsEntry("accepted", false)
                .containsEntry("code", "EDIT_PERMISSION_REQUIRED");
    }

    @Test
    void sameSeedProducesSameResultAcrossRuns() {
        CollaborationScenario first = seededScenario(36005);
        CollaborationScenario second = seededScenario(36005);

        ScenarioResult firstResult = harness().run(first);
        ScenarioResult secondResult = harness().run(second);

        assertThat(secondResult.finalContent()).isEqualTo(firstResult.finalContent());
        assertThat(secondResult.acceptedCount()).isEqualTo(firstResult.acceptedCount());
        assertThat(secondResult.rejectedCount()).isEqualTo(firstResult.rejectedCount());
        assertThat(secondResult.currentRevision()).isEqualTo(firstResult.currentRevision());
    }

    private DeterministicCollaborationHarness harness() {
        return new DeterministicCollaborationHarness(restTemplate, baseUrl, jdbcTemplate, operationService, documentStateService);
    }

    private CollaborationScenario seededScenario(long seed) {
        Random random = new Random(seed);
        List<ScriptedOperation> operations = new ArrayList<>();
        StringBuilder expected = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            char letter = (char) ('a' + random.nextInt(26));
            operations.add(insert(i % 2 == 0 ? "owner" : "editor", "seed-" + seed + "-" + i, i + 1,
                    i, expected.length(), Character.toString(letter)));
            expected.append(letter);
        }
        return new CollaborationScenario(seed, operations);
    }

    private ScriptedOperation insert(String clientKey, String operationId, long clientSeq, long baseRevision, int position, String text) {
        return new ScriptedOperation(clientKey, operationId, clientSeq, baseRevision, "TEXT_INSERT",
                Map.of("position", position, "text", text));
    }
}
