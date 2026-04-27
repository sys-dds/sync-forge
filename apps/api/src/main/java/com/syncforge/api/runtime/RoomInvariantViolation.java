package com.syncforge.api.runtime;

public record RoomInvariantViolation(
        String code,
        String severity,
        String message,
        String expected,
        String actual,
        Long relatedRoomSeq,
        RecommendedRuntimeAction recommendedAction
) {
    public RoomInvariantViolation(
            String code,
            String severity,
            String message,
            String expected,
            String actual,
            Long relatedRoomSeq) {
        this(code, severity, message, expected, actual, relatedRoomSeq, RecommendedRuntimeAction.PAUSE_AND_REPAIR);
    }
}
