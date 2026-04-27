package com.syncforge.api.runtime;

public record RoomInvariantViolation(
        String code,
        String severity,
        String message,
        String expected,
        String actual,
        Long relatedRoomSeq
) {
}
