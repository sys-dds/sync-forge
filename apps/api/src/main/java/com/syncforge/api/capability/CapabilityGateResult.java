package com.syncforge.api.capability;

public record CapabilityGateResult(
        boolean allowed,
        String code,
        String reason
) {
    public static CapabilityGateResult allow() {
        return new CapabilityGateResult(true, null, null);
    }

    public static CapabilityGateResult rejected(String code, String reason) {
        return new CapabilityGateResult(false, code, reason);
    }
}
