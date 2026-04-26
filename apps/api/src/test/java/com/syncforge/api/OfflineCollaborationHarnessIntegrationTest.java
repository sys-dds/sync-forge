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
class OfflineCollaborationHarnessIntegrationTest extends AbstractIntegrationTest {
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
    void deterministicOfflineHarnessDrivesReconnectBackfillAndReplayChecks() {
        Fixture fixture = fixture();
        OfflineCollaborationHarness harness = harness(fixture);
        harness.connect("owner");
        harness.connect("editor");

        OperationSubmitResult online = harness.submitOnline("owner", "harness-online-1", 1, 0,
                "TEXT_INSERT", Map.of("position", 0, "text", "A"));
        harness.disconnect("editor");
        OperationSubmitResult offline = harness.submitOffline("editor", "harness-offline-1", "harness-client-op-1",
                1, 1, 1, "TEXT_INSERT", Map.of("position", 1, "text", "B"), 1L, List.of("harness-online-1"));

        assertThat(online.accepted()).isTrue();
        assertThat(offline.accepted()).isTrue();
        assertThat(harness.dispatchOutbox(10)).isEqualTo(2);
        assertThat(harness.outboxCount(RoomEventOutboxStatus.PUBLISHED)).isEqualTo(2);

        harness.connect("editor");
        BackfillResult backfill = harness.backfill("editor", harness.lastAcked("editor"));
        assertThat(backfill.events()).extracting(event -> event.get("roomSeq")).containsExactly(1L, 2L);
        harness.assertNoDuplicateFanout(backfill.events());
        assertThat(harness.ack("editor", backfill.toRoomSeq())).isTrue();
        assertThat(harness.lastAcked("editor")).isEqualTo(2);
        assertThat(harness.liveState().contentText()).isEqualTo("AB");
        harness.assertReplayEqualsLive();
        assertThat(harness.acceptedOperationCount()).isEqualTo(2);
    }

    private OfflineCollaborationHarness harness(Fixture fixture) {
        return new OfflineCollaborationHarness(
                fixture.roomId(),
                Map.of("owner", fixture.ownerId(), "editor", fixture.editorId()),
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
