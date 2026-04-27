package com.syncforge.api.snapshot.api;

import java.util.UUID;

public record SnapshotReplayResponse(
        UUID roomId,
        UUID snapshotId,
        long snapshotRoomSeq,
        int operationsReplayed,
        int tailOperationsReplayed,
        long replayedToRoomSeq,
        long resultingRoomSeq,
        long resultingRevision,
        String resultingChecksum,
        boolean checksumVerified,
        boolean replayEquivalent
) {
}
