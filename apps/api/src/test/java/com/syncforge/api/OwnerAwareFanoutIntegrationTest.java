package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import com.syncforge.api.stream.application.RoomEventStreamConsumer;
import com.syncforge.api.stream.application.RoomStreamKeyFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "syncforge.redis.stream.enabled=true")
class OwnerAwareFanoutIntegrationTest extends RoomOwnershipTestSupport {
    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    RoomStreamKeyFactory keyFactory;

    @Autowired
    RoomEventStreamConsumer streamConsumer;

    @Test
    void staleUnsafeStreamEventIsIgnoredAndCanonicalPreTakeoverEventRemainsRecoverable() {
        Fixture fixture = fixture();
        var nodeA = acquire(fixture, NODE_A);
        submitAs(fixture, NODE_A, nodeA.fencingToken(), "fanout-canonical", 1, 0, Map.of("text", "A"));
        takeover(fixture, NODE_B, 1);

        redisTemplate.opsForStream().add(keyFactory.roomStreamKey(fixture.roomId()), Map.ofEntries(
                Map.entry("eventId", fixture.roomId() + ":2-stale"),
                Map.entry("roomId", fixture.roomId().toString()),
                Map.entry("roomSeq", "2"),
                Map.entry("revision", "2"),
                Map.entry("operationId", "not-canonical"),
                Map.entry("userId", fixture.editorId().toString()),
                Map.entry("clientSeq", "2"),
                Map.entry("operationType", "TEXT_INSERT_AFTER"),
                Map.entry("operation", "{\"text\":\"bad\"}"),
                Map.entry("transformed", "false"),
                Map.entry("producedByNodeId", NODE_A),
                Map.entry("ownerNodeId", NODE_A),
                Map.entry("fencingToken", Long.toString(nodeA.fencingToken())),
                Map.entry("createdAt", java.time.OffsetDateTime.now().toString())));

        assertThat(streamConsumer.pollRoom(fixture.roomId())).isZero();
        assertThat(resumeWindowService.decide(fixture.roomId(), fixture.editorId(), 0).operations())
                .extracting(operation -> operation.get("operationId"))
                .containsExactly("fanout-canonical");
    }
}
