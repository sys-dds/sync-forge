package com.syncforge.api.capability;

import java.util.List;
import java.util.Set;

public record CapabilityNegotiationResult(
        int protocolVersion,
        Set<ClientCapability> enabledCapabilities,
        List<CapabilityNegotiationItem> disabledCapabilities,
        List<CapabilityNegotiationItem> rejectedCapabilities
) {
    public boolean enabled(ClientCapability capability) {
        return enabledCapabilities.contains(capability);
    }
}
