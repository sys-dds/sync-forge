package com.syncforge.api.runtime;

import java.util.UUID;

public record RoomRepairRebuildResponse(
        UUID rebuildId,
        UUID roomId,
        long snapshotRoomSeq,
        int tailOperationsReplayed,
        long rebuiltToRoomSeq,
        String checksum,
        String status
) {
}
