package com.syncforge.api.harness;

import java.util.List;
import java.util.Map;

public record ScenarioResult(
        long seed,
        String finalContent,
        long currentRevision,
        long currentRoomSeq,
        int acceptedCount,
        int rejectedCount,
        List<Map<String, Object>> outcomes) {
}
