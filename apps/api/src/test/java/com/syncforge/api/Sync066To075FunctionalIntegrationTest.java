package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.syncforge.api.delivery.RoomEventOutboxDispatcher;
import com.syncforge.api.delivery.RoomEventOutboxRepository;
import com.syncforge.api.delivery.RoomEventOutboxStatus;
import com.syncforge.api.documentstate.application.DocumentStateService;
import com.syncforge.api.harness.OfflineCollaborationHarness;
import com.syncforge.api.operation.application.CanonicalOperationPayloadHasher;
import com.syncforge.api.operation.application.OperationService;
import com.syncforge.api.operation.model.OperationSubmitResult;
import com.syncforge.api.resume.application.ClientOffsetService;
import com.syncforge.api.resume.application.RoomBackfillService;
import com.syncforge.api.resume.model.BackfillResult;
import com.syncforge.api.stream.application.NodeRoomSubscriptionService;
import com.syncforge.api.stream.application.RoomEventStreamConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "syncforge.redis.stream.enabled=true")
class Sync066To075FunctionalIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    OperationService operationService;

    @Autowired
    CanonicalOperationPayloadHasher payloadHasher;

    @Autowired
    DocumentStateService documentStateService;

    @Autowired
    RoomEventOutboxDispatcher outboxDispatcher;

    @Autowired
    RoomEventStreamConsumer streamConsumer;

    @Autowired
    NodeRoomSubscriptionService subscriptionService;

    @Autowired
    ClientOffsetService clientOffsetService;

    @Autowired
    RoomBackfillService roomBackfillService;

    @Autowired
    RoomEventOutboxRepository outboxRepository;

    @Test
    void sync066To075EndToEndOfflineReconnectReplayAndPermissionSafety() {
        Fixture fixture = fixture();
        OfflineCollaborationHarness harness = harness(fixture);
        harness.connect("clientA");
        harness.connect("clientB");
        harness.disconnect("clientB");

        OperationSubmitResult online = harness.submitOnline("clientA", "sync066-online-a", 1, 0,
                "TEXT_INSERT", Map.of("position", 0, "text", "A"));
        assertThat(online.accepted()).isTrue();
        assertThat(harness.dispatchOutbox(10)).isEqualTo(1);
        assertThat(harness.outboxCount(RoomEventOutboxStatus.PUBLISHED)).isEqualTo(1);

        OperationSubmitResult offline = harness.submitOffline("clientB", "sync066-offline-b", "sync066-client-op-b",
                1, 1, 1, "TEXT_INSERT", Map.of("position", 1, "text", "B"), 1L, List.of("sync066-online-a"));
        OperationSubmitResult duplicateOffline = harness.submitOffline(
                "clientB",
                "sync066-offline-b-duplicate",
                "sync066-client-op-b",
                2,
                1,
                1,
                "TEXT_INSERT",
                Map.of("position", 1, "text", "B"),
                1L,
                List.of("sync066-online-a"));

        assertThat(offline.accepted()).isTrue();
        assertThat(duplicateOffline.accepted()).isTrue();
        assertThat(duplicateOffline.duplicate()).isTrue();
        assertThat(duplicateOffline.roomSeq()).isEqualTo(offline.roomSeq());
        assertThat(harness.acceptedOperationCount()).isEqualTo(2);
        assertThat(harness.outboxForRoomSeq(2)).isPresent();
        assertThat(harness.dispatchOutbox(10)).isEqualTo(1);
        assertThat(harness.pollRoomStream()).isEqualTo(2);

        harness.connect("clientB");
        BackfillResult backfill = harness.backfill("clientB", 0);
        assertThat(backfill.outcome()).isEqualTo("BACKFILLED");
        assertThat(backfill.events()).extracting(event -> event.get("operationId"))
                .containsExactly("sync066-online-a", "sync066-offline-b");
        harness.assertNoDuplicateFanout(backfill.events());
        assertThat(harness.ack("clientB", backfill.toRoomSeq())).isTrue();
        assertThat(harness.lastAcked("clientB")).isEqualTo(2);
        assertThat(harness.liveState().contentText()).isEqualTo("AB");
        harness.assertReplayEqualsLive();

        harness.removeMember("clientB");
        OperationSubmitResult denied = harness.submitOffline("clientB", "sync066-denied", "sync066-client-op-denied",
                3, 2, 2, "TEXT_INSERT", Map.of("position", 2, "text", "!"), null, List.of());
        assertThat(denied.accepted()).isFalse();
        assertThat(denied.code()).isEqualTo("EDIT_PERMISSION_REQUIRED");
        assertThat(harness.liveState().contentText()).isEqualTo("AB");
        assertThat(harness.acceptedOperationCount()).isEqualTo(2);
        assertThat(harness.outboxForRoomSeq(3)).isEmpty();
    }

    private OfflineCollaborationHarness harness(Fixture fixture) {
        return new OfflineCollaborationHarness(
                fixture.roomId(),
                Map.of("clientA", fixture.ownerId(), "clientB", fixture.editorId()),
                operationService,
                payloadHasher,
                documentStateService,
                outboxDispatcher,
                streamConsumer,
                subscriptionService,
                clientOffsetService,
                roomBackfillService,
                outboxRepository,
                jdbcTemplate);
    }
}
