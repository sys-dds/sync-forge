package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.syncforge.api.documentstate.application.DocumentStateService;
import com.syncforge.api.operation.application.OperationService;
import com.syncforge.api.operation.model.SubmitOperationCommand;
import com.syncforge.api.text.application.TextConvergenceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class VisibleTextMaterializationIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    OperationService operationService;

    @Autowired
    DocumentStateService documentStateService;

    @Autowired
    TextConvergenceService textConvergenceService;

    @Test
    void materializedVisibleTextExcludesTombstonesAndMatchesDocumentStateFetch() {
        Fixture fixture = fixture();
        submit(fixture, "visible-a", 1, 0, "TEXT_INSERT_AFTER", Map.of("text", "A"));
        submit(fixture, "visible-b", 2, 1, "TEXT_INSERT_AFTER", Map.of("anchorAtomId", "visible-a:0", "text", "B"));
        submit(fixture, "visible-c", 3, 2, "TEXT_INSERT_AFTER", Map.of("anchorAtomId", "visible-b:0", "text", "C"));
        submit(fixture, "hide-b", 4, 3, "TEXT_DELETE_ATOMS", Map.of("atomIds", List.of("visible-b:0")));

        String firstMaterialization = textConvergenceService.materializeVisibleText(fixture.roomId());
        String secondMaterialization = textConvergenceService.materializeVisibleText(fixture.roomId());
        Map<String, Object> response = getMap("/api/v1/rooms/" + fixture.roomId()
                + "/document-state?userId=" + fixture.viewerId());

        assertThat(firstMaterialization).isEqualTo("AC");
        assertThat(secondMaterialization).isEqualTo(firstMaterialization);
        assertThat(documentStateService.getOrInitialize(fixture.roomId()).contentText()).isEqualTo(firstMaterialization);
        assertThat(response.get("contentText")).isEqualTo(firstMaterialization);
    }

    private void submit(Fixture fixture, String operationId, long clientSeq, long baseRevision, String operationType,
            Map<String, Object> operation) {
        assertThat(operationService.submit(new SubmitOperationCommand(
                fixture.roomId(),
                fixture.editorId(),
                "connection-" + operationId,
                "session-" + operationId,
                operationId,
                clientSeq,
                baseRevision,
                operationType,
                operation)).accepted()).isTrue();
    }
}
