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
class StaleOwnerStreamEventIntegrationTest extends RoomOwnershipTestSupport {
    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    RoomStreamKeyFactory keyFactory;

    @Autowired
    RoomEventStreamConsumer streamConsumer;

    @Test
    void duplicateOldOwnerStreamEventDoesNotDoubleDeliverOrLeakAcrossRooms() {
        Fixture fixture = fixture();
        Fixture other = fixture();
        var nodeA = acquire(fixture, NODE_A);
        var otherNodeA = acquire(other, NODE_A);
        takeover(fixture, NODE_B, 1);
        takeover(other, NODE_B, 1);

        redisTemplate.opsForStream().add(keyFactory.roomStreamKey(fixture.roomId()), stale(fixture, nodeA.fencingToken()));
        redisTemplate.opsForStream().add(keyFactory.roomStreamKey(other.roomId()), stale(other, otherNodeA.fencingToken()));

        assertThat(streamConsumer.pollRoom(fixture.roomId())).isZero();
        assertThat(streamConsumer.pollRoom(other.roomId())).isZero();
        assertThat(operationCount(fixture.roomId())).isZero();
        assertThat(operationCount(other.roomId())).isZero();
    }

    private Map<String, String> stale(Fixture fixture, long token) {
        return Map.ofEntries(
                Map.entry("eventId", fixture.roomId() + ":1-stale"),
                Map.entry("roomId", fixture.roomId().toString()),
                Map.entry("roomSeq", "1"),
                Map.entry("revision", "1"),
                Map.entry("operationId", "stale-stream"),
                Map.entry("userId", fixture.editorId().toString()),
                Map.entry("clientSeq", "1"),
                Map.entry("operationType", "TEXT_INSERT_AFTER"),
                Map.entry("operation", "{\"text\":\"bad\"}"),
                Map.entry("transformed", "false"),
                Map.entry("producedByNodeId", NODE_A),
                Map.entry("ownerNodeId", NODE_A),
                Map.entry("fencingToken", Long.toString(token)),
                Map.entry("createdAt", java.time.OffsetDateTime.now().toString()));
    }
}
