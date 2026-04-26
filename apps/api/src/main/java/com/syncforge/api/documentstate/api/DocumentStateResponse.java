package com.syncforge.api.documentstate.api;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.syncforge.api.documentstate.model.DocumentLiveState;

public record DocumentStateResponse(
        UUID roomId,
        UUID documentId,
        long currentRoomSeq,
        long currentRevision,
        String contentText,
        String contentChecksum,
        OffsetDateTime updatedAt
) {
    public static DocumentStateResponse from(DocumentLiveState state) {
        return new DocumentStateResponse(
                state.roomId(),
                state.documentId(),
                state.currentRoomSeq(),
                state.currentRevision(),
                state.contentText(),
                state.contentChecksum(),
                state.updatedAt());
    }
}
