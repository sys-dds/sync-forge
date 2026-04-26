package com.syncforge.api.stream.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RoomEventStreamProperties {
    private final String roomKeyPrefix;
    private final long maxLen;
    private final long consumerPollMs;
    private final boolean enabled;

    public RoomEventStreamProperties(
            @Value("${syncforge.redis.stream.room-key-prefix:syncforge:room-events:}") String roomKeyPrefix,
            @Value("${syncforge.redis.stream.maxlen:10000}") long maxLen,
            @Value("${syncforge.redis.stream.consumer-poll-ms:100}") long consumerPollMs,
            @Value("${syncforge.redis.stream.enabled:true}") boolean enabled) {
        this.roomKeyPrefix = roomKeyPrefix;
        this.maxLen = maxLen;
        this.consumerPollMs = consumerPollMs;
        this.enabled = enabled;
    }

    public String roomKeyPrefix() {
        return roomKeyPrefix;
    }

    public long maxLen() {
        return maxLen;
    }

    public long consumerPollMs() {
        return consumerPollMs;
    }

    public boolean enabled() {
        return enabled;
    }
}
