package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import com.syncforge.api.delivery.RoomEventOutboxRecord;
import com.syncforge.api.delivery.RoomEventOutboxRepository;
import com.syncforge.api.operation.application.OperationService;
import com.syncforge.api.operation.model.OperationSubmitResult;
import com.syncforge.api.operation.model.SubmitOperationCommand;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class RoomEventOutboxTransactionIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    OperationService operationService;

    @Autowired
    RoomEventOutboxRepository outboxRepository;

    @Test
    void acceptedRejectedAndDuplicateOperationsHaveCorrectOutboxIntent() {
        Fixture fixture = fixture();

        OperationSubmitResult accepted = submit(fixture, "outbox-tx-accepted", 1, 0,
                Map.of("position", 0, "text", "a"));
        assertThat(accepted.accepted()).isTrue();
        RoomEventOutboxRecord outbox = outboxRepository.findByRoomSeq(fixture.roomId(), accepted.roomSeq()).orElseThrow();
        assertThat(outbox.operationId()).isEqualTo("outbox-tx-accepted");
        assertThat(outbox.logicalEventKey()).isEqualTo(fixture.roomId() + ":" + accepted.roomSeq());
        assertThat(outbox.payload())
                .containsEntry("operationId", "outbox-tx-accepted")
                .containsEntry("roomSeq", 1)
                .containsEntry("revision", 1)
                .containsEntry("operationType", "TEXT_INSERT");
        assertThat(outbox.payload().get("operation")).isEqualTo(Map.of("position", 0, "text", "a"));

        OperationSubmitResult rejected = submit(fixture, "outbox-tx-rejected", 2, 1,
                Map.of("position", 99, "text", "bad"));
        assertThat(rejected.accepted()).isFalse();
        assertThat(outboxRepository.findByRoomSeq(fixture.roomId(), 2)).isEmpty();

        OperationSubmitResult duplicate = submit(fixture, "outbox-tx-accepted", 99, 0,
                Map.of("position", 0, "text", "a"));
        assertThat(duplicate.accepted()).isTrue();
        assertThat(duplicate.duplicate()).isTrue();
        assertThat(jdbcTemplate.queryForObject("""
                select count(*)
                from room_event_outbox
                where room_id = ?
                """, Long.class, fixture.roomId())).isEqualTo(1);
    }

    private OperationSubmitResult submit(Fixture fixture, String operationId, long clientSeq, long baseRevision,
            Map<String, Object> operation) {
        return operationService.submit(new SubmitOperationCommand(
                fixture.roomId(),
                fixture.editorId(),
                "outbox-tx-connection",
                "outbox-tx-session",
                operationId,
                clientSeq,
                baseRevision,
                "TEXT_INSERT",
                operation));
    }
}
