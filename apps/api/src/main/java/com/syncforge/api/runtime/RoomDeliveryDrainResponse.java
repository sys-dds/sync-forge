package com.syncforge.api.runtime;

import java.util.UUID;

public record RoomDeliveryDrainResponse(
        UUID roomId,
        int attempted,
        int published,
        int retried,
        int skipped,
        long remainingPending
) {
}
