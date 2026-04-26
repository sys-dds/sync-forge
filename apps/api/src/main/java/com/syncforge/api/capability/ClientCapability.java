package com.syncforge.api.capability;

import java.util.Optional;

public enum ClientCapability {
    OPERATIONS,
    AWARENESS,
    PRESENCE,
    RESUME,
    BACKFILL,
    SNAPSHOT,
    OFFLINE_EDITS,
    CAUSAL_DEPENDENCIES,
    CRDT_TEXT_PREVIEW,
    COMPRESSION;

    public static Optional<ClientCapability> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(ClientCapability.valueOf(raw.trim().toUpperCase()));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}
