package com.syncforge.api.runtime;

import java.time.OffsetDateTime;
import java.util.UUID;

public record RoomRuntimeControlState(
        UUID roomId,
        boolean writesPaused,
        long forceResyncGeneration,
        String forceResyncReason,
        boolean repairRequired,
        String lastControlAction,
        String lastControlReason,
        UUID lastControlActor,
        OffsetDateTime updatedAt
) {
}
