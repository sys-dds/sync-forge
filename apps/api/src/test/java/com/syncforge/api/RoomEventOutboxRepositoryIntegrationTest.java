package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import com.syncforge.api.delivery.RoomEventOutboxRecord;
import com.syncforge.api.delivery.RoomEventOutboxRepository;
import com.syncforge.api.delivery.RoomEventOutboxService;
import com.syncforge.api.delivery.RoomEventOutboxStatus;
import com.syncforge.api.delivery.RoomEventPayloadFactory;
import com.syncforge.api.operation.model.OperationRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class RoomEventOutboxRepositoryIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    RoomEventOutboxService outboxService;

    @Autowired
    RoomEventOutboxRepository outboxRepository;

    @Test
    void createsCanonicalPendingOperationPayloadAndCanFindByRoomSeq() {
        Fixture fixture = fixture();
        OperationRecord operation = operation(fixture, "outbox-repo-1", 1, 1, Map.of("position", 0, "text", "a"));

        RoomEventOutboxRecord record = outboxService.createPendingOperationEvent(operation, false);

        assertThat(record.status()).isEqualTo(RoomEventOutboxStatus.PENDING);
        assertThat(record.logicalEventKey()).isEqualTo(fixture.roomId() + ":1");
        assertThat(record.eventType()).isEqualTo(RoomEventPayloadFactory.OPERATION_APPLIED);
        assertThat(record.payload())
                .containsEntry("eventId", fixture.roomId() + ":1")
                .containsEntry("roomId", fixture.roomId().toString())
                .containsEntry("roomSeq", 1)
                .containsEntry("revision", 1)
                .containsEntry("operationId", "outbox-repo-1")
                .containsEntry("userId", fixture.editorId().toString())
                .containsEntry("clientSeq", 1)
                .containsEntry("operationType", "TEXT_INSERT")
                .containsEntry("transformed", false);
        assertThat(record.payload().get("operation")).isEqualTo(Map.of("position", 0, "text", "a"));
        assertThat(outboxService.findByRoomSeq(fixture.roomId(), 1)).contains(record);
        assertThat(outboxService.countByStatus(RoomEventOutboxStatus.PENDING)).isEqualTo(1);
    }

    @Test
    void duplicateLogicalEventKeyReturnsExistingRowWithoutSecondPendingEvent() {
        Fixture fixture = fixture();
        OperationRecord operation = operation(fixture, "outbox-repo-duplicate", 1, 1, Map.of("position", 0, "text", "a"));

        RoomEventOutboxRecord first = outboxService.createPendingOperationEvent(operation, false);
        RoomEventOutboxRecord duplicate = outboxService.createPendingOperationEvent(operation, false);

        assertThat(duplicate.id()).isEqualTo(first.id());
        assertThat(outboxService.countByStatus(RoomEventOutboxStatus.PENDING)).isEqualTo(1);
        assertThat(outboxRepository.findByLogicalEventKey(fixture.roomId() + ":1")).contains(first);
    }

    private OperationRecord operation(Fixture fixture, String operationId, long roomSeq, long revision, Map<String, Object> payload) {
        return new OperationRecord(
                UUID.randomUUID(),
                fixture.roomId(),
                fixture.editorId(),
                "outbox-repo-connection",
                operationId,
                "outbox-repo-session",
                roomSeq,
                revision - 1,
                roomSeq,
                revision,
                "TEXT_INSERT",
                payload,
                "local-node-1",
                1L,
                OffsetDateTime.now());
    }
}
