package com.syncforge.api.capability;

public final class CapabilityErrorCode {
    public static final String UNKNOWN_CAPABILITY = "UNKNOWN_CAPABILITY";
    public static final String CAPABILITY_RESERVED_FOR_FUTURE = "CAPABILITY_RESERVED_FOR_FUTURE";
    public static final String CAPABILITY_NOT_NEGOTIATED = "CAPABILITY_NOT_NEGOTIATED";
    public static final String RESUME_NOT_NEGOTIATED = "RESUME_NOT_NEGOTIATED";
    public static final String BACKFILL_NOT_NEGOTIATED = "BACKFILL_NOT_NEGOTIATED";
    public static final String SNAPSHOT_NOT_NEGOTIATED = "SNAPSHOT_NOT_NEGOTIATED";
    public static final String AWARENESS_NOT_NEGOTIATED = "AWARENESS_NOT_NEGOTIATED";
    public static final String OPERATIONS_NOT_NEGOTIATED = "OPERATIONS_NOT_NEGOTIATED";
    public static final String NEGOTIATION_REQUIRED = "NEGOTIATION_REQUIRED";

    private CapabilityErrorCode() {
    }
}
