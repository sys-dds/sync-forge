package com.syncforge.api.runtime;

public enum RecommendedRuntimeAction {
    NONE,
    FORCE_RESYNC,
    DRAIN_OUTBOX,
    PAUSE_AND_REPAIR,
    SNAPSHOT_REFRESH_REQUIRED
}
