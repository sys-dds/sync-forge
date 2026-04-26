package com.syncforge.api.delivery;

public final class DeliveryTruthModel {
    private DeliveryTruthModel() {
    }

    public static final String DB_OPERATION_LOG = "CANONICAL_TRUTH";
    public static final String REDIS_STREAM = "DELIVERY_PIPE";
    public static final String WEBSOCKET_FANOUT = "LOCAL_DELIVERY";
    public static final String CLIENT_ACK = "CLIENT_PROGRESS";

    public static boolean requiresOutboxIntent(boolean accepted, boolean duplicate) {
        return accepted && !duplicate;
    }

    public static boolean mayCreateOutboxIntent(boolean accepted, boolean duplicate) {
        return requiresOutboxIntent(accepted, duplicate);
    }

    public static boolean clientAckIsCanonicalTruth() {
        return false;
    }
}
