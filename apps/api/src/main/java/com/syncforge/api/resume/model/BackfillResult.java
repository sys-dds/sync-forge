package com.syncforge.api.resume.model;

import java.util.List;
import java.util.Map;

import com.syncforge.api.documentstate.model.DocumentLiveState;

public record BackfillResult(
        String outcome,
        long fromRoomSeq,
        long toRoomSeq,
        List<Map<String, Object>> events,
        DocumentLiveState currentState,
        String reason
) {
    public boolean resyncRequired() {
        return "RESYNC_REQUIRED".equals(outcome);
    }
}
