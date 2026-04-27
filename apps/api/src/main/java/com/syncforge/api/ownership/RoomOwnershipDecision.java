package com.syncforge.api.ownership;

public record RoomOwnershipDecision(
        boolean accepted,
        String code,
        String message,
        RoomOwnershipLease lease
) {
    public static RoomOwnershipDecision accepted(RoomOwnershipLease lease) {
        return new RoomOwnershipDecision(true, null, null, lease);
    }

    public static RoomOwnershipDecision rejected(String code, String message, RoomOwnershipLease lease) {
        return new RoomOwnershipDecision(false, code, message, lease);
    }
}
