package com.syncforge.api.stream.application;

import java.util.UUID;

import org.springframework.stereotype.Component;

@Component
public class RoomStreamKeyFactory {
    private final RoomEventStreamProperties properties;

    public RoomStreamKeyFactory(RoomEventStreamProperties properties) {
        this.properties = properties;
    }

    public String roomStreamKey(UUID roomId) {
        return properties.roomKeyPrefix() + roomId;
    }
}
