package com.syncforge.api.capability;

public record CapabilityNegotiationItem(
        String capability,
        String code,
        String reason
) {
}
