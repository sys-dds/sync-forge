package com.syncforge.api.documentstate.api;

import java.util.UUID;

import com.syncforge.api.documentstate.model.DocumentLiveState;

public record RebuildDocumentStateResponse(
        UUID roomId,
        long operationsReplayed,
        long currentRoomSeq,
        long currentRevision,
        String contentChecksum,
        boolean replayEquivalent
) {
    public static RebuildDocumentStateResponse from(DocumentLiveState state, long operationsReplayed, boolean replayEquivalent) {
        return new RebuildDocumentStateResponse(
                state.roomId(),
                operationsReplayed,
                state.currentRoomSeq(),
                state.currentRevision(),
                state.contentChecksum(),
                replayEquivalent);
    }
}
