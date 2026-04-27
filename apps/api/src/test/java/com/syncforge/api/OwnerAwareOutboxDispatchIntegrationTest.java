package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import com.syncforge.api.delivery.RoomEventOutboxStatus;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "syncforge.redis.stream.enabled=true")
class OwnerAwareOutboxDispatchIntegrationTest extends RoomOwnershipTestSupport {
    @Test
    void currentOwnerPublishesStaleOwnerIsRejectedAndTakeoverOwnerCanPublishPendingRows() {
        Fixture fixture = fixture();
        var local = submitAcceptedText(fixture, "owner-outbox-local", 1, 0, "TEXT_INSERT_AFTER", Map.of("text", "A"));
        assertThat(dispatcherDispatch()).isEqualTo(1);
        assertThat(outboxRepository.findByRoomSeq(fixture.roomId(), local.roomSeq()).orElseThrow().status())
                .isEqualTo(RoomEventOutboxStatus.PUBLISHED);

        Fixture takeoverFixture = fixture();
        var nodeA = acquire(takeoverFixture, NODE_A);
        var pending = submitAs(takeoverFixture, NODE_A, nodeA.fencingToken(), "owner-outbox-pending", 1, 0, Map.of("text", "P"));
        var localTakeover = ownershipService.takeoverExpired(takeoverFixture.roomId(), "test-node-1", "DISPATCH_TAKEOVER",
                nodeA.leaseExpiresAt().plusSeconds(1));
        assertThat(localTakeover.ownerNodeId()).isEqualTo("test-node-1");

        assertThat(dispatcherDispatch()).isEqualTo(1);
        assertThat(outboxRepository.findByRoomSeq(takeoverFixture.roomId(), pending.roomSeq()).orElseThrow().status())
                .isEqualTo(RoomEventOutboxStatus.PUBLISHED);
    }

    @Test
    void staleLocalOwnerCannotPublishAfterLosingLease() {
        Fixture fixture = fixture();
        submitAcceptedText(fixture, "stale-publish-row", 1, 0, "TEXT_INSERT_AFTER", Map.of("text", "A"));
        var local = ownershipService.currentOwnership(fixture.roomId());
        takeover(fixture, NODE_B, 1);

        assertThat(dispatcherDispatch()).isZero();
        assertThat(outboxRepository.findByRoomSeq(fixture.roomId(), 1).orElseThrow().status())
                .isEqualTo(RoomEventOutboxStatus.RETRY);
        assertThat(ownershipRepository.events(fixture.roomId()))
                .extracting("eventType")
                .contains("FENCED_PUBLISH_REJECTED");
        assertThat(local.ownerNodeId()).isEqualTo("test-node-1");
    }

    private int dispatcherDispatch() {
        return outboxDispatcher.dispatchOnce(10);
    }
}
