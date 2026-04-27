package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import com.syncforge.api.stream.application.RoomStreamKeyFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "syncforge.redis.stream.enabled=true")
class OwnerAwareStreamPublishIntegrationTest extends RoomOwnershipTestSupport {
    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    RoomStreamKeyFactory keyFactory;

    @Test
    void publishedStreamEventCarriesOwnerFencingMetadataAndDuplicateDispatchIsDeduped() {
        Fixture fixture = fixture();
        var accepted = submitAcceptedText(fixture, "owner-stream-a", 1, 0, "TEXT_INSERT_AFTER", Map.of("text", "A"));

        assertThat(outboxDispatcher.dispatchOnce(10)).isEqualTo(1);
        assertThat(outboxDispatcher.dispatchOnce(10)).isZero();

        var records = redisTemplate.opsForStream().range(keyFactory.roomStreamKey(fixture.roomId()), Range.unbounded());
        assertThat(records).hasSize(1);
        assertThat(records.getFirst().getValue())
                .containsEntry("ownerNodeId", "test-node-1")
                .containsEntry("fencingToken", "1")
                .containsEntry("roomSeq", accepted.roomSeq().toString());
    }
}
