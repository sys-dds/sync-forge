package com.syncforge.api.delivery;

public enum RoomEventOutboxStatus {
    PENDING,
    PUBLISHING,
    RETRY,
    PUBLISHED,
    PARKED
}
