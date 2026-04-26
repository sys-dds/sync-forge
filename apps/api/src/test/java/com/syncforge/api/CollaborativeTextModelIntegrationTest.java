package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import com.syncforge.api.shared.BadRequestException;
import com.syncforge.api.text.application.TextConvergenceService;
import com.syncforge.api.text.model.CollaborativeTextOperation;
import com.syncforge.api.text.model.TextAnchor;
import com.syncforge.api.text.model.TextOperationType;
import org.junit.jupiter.api.Test;

class CollaborativeTextModelIntegrationTest {
    private final TextConvergenceService service = new TextConvergenceService(null);

    @Test
    void parsesInsertAfterStartFoundation() {
        CollaborativeTextOperation operation = service.parseAndValidate("TEXT_INSERT_AFTER", Map.of("text", "hello"));

        assertThat(operation.type()).isEqualTo(TextOperationType.TEXT_INSERT_AFTER);
        assertThat(operation.anchor()).isEqualTo(TextAnchor.start());
        assertThat(operation.content()).isEqualTo("hello");
    }

    @Test
    void parsesInsertAfterExistingAtomFoundation() {
        CollaborativeTextOperation operation = service.parseAndValidate("TEXT_INSERT_AFTER",
                Map.of("anchorAtomId", "op-1:0", "text", " world"));

        assertThat(operation.anchor()).isEqualTo(TextAnchor.after("op-1:0"));
        assertThat(operation.anchor().isStart()).isFalse();
    }

    @Test
    void parsesTombstoneDeleteFoundation() {
        CollaborativeTextOperation operation = service.parseAndValidate("TEXT_DELETE_ATOMS",
                Map.of("atomIds", List.of("op-1:0", "op-2:0")));

        assertThat(operation.type()).isEqualTo(TextOperationType.TEXT_DELETE_ATOMS);
        assertThat(operation.targetAtomIds()).containsExactly("op-1:0", "op-2:0");
    }

    @Test
    void rejectsUnsafeFoundationOperationsDeterministically() {
        assertThatThrownBy(() -> service.parseAndValidate("TEXT_INSERT_AFTER", Map.of("text", "")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("text is required");

        assertThatThrownBy(() -> service.parseAndValidate("TEXT_DELETE_ATOMS", Map.of("atomIds", List.of())))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("atomIds is required");
    }

    @Test
    void stableAtomIdsAreDeterministic() {
        assertThat(service.stableAtomId("op-123", 0)).isEqualTo("op-123:0");
        assertThat(service.stableAtomId("op-123", 0)).isEqualTo(service.stableAtomId("op-123", 0));
    }
}
