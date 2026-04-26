package com.syncforge.api.capability;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

@Service
public class ClientCapabilityService {
    private static final Set<ClientCapability> ACTIVE_CAPABILITIES = Set.of(
            ClientCapability.OPERATIONS,
            ClientCapability.AWARENESS,
            ClientCapability.PRESENCE,
            ClientCapability.RESUME,
            ClientCapability.BACKFILL,
            ClientCapability.SNAPSHOT);

    private static final Set<ClientCapability> RESERVED_CAPABILITIES = Set.of(
            ClientCapability.OFFLINE_EDITS,
            ClientCapability.CAUSAL_DEPENDENCIES,
            ClientCapability.CRDT_TEXT_PREVIEW,
            ClientCapability.COMPRESSION);

    public CapabilityNegotiationResult negotiate(int protocolVersion, Collection<?> requestedCapabilities) {
        LinkedHashSet<ClientCapability> requestedKnown = new LinkedHashSet<>();
        List<CapabilityNegotiationItem> rejected = new ArrayList<>();
        for (Object requested : requestedCapabilities == null ? List.of() : requestedCapabilities) {
            String raw = requested == null ? null : requested.toString();
            ClientCapability.parse(raw).ifPresentOrElse(
                    requestedKnown::add,
                    () -> rejected.add(new CapabilityNegotiationItem(
                            raw == null ? "" : raw,
                            CapabilityErrorCode.UNKNOWN_CAPABILITY,
                            "Capability is not recognized.")));
        }

        LinkedHashSet<ClientCapability> enabled = new LinkedHashSet<>();
        List<CapabilityNegotiationItem> disabled = new ArrayList<>();
        if (protocolVersion == 1) {
            enabled.addAll(sorted(ACTIVE_CAPABILITIES));
            for (ClientCapability reserved : sorted(RESERVED_CAPABILITIES)) {
                disabled.add(reservedItem(reserved));
            }
            return new CapabilityNegotiationResult(protocolVersion, enabled, disabled, rejected);
        }

        for (ClientCapability capability : sorted(ACTIVE_CAPABILITIES)) {
            if (requestedKnown.contains(capability)) {
                enabled.add(capability);
            } else {
                disabled.add(new CapabilityNegotiationItem(
                        capability.name(),
                        CapabilityErrorCode.CAPABILITY_NOT_NEGOTIATED,
                        "Capability was not requested."));
            }
        }
        for (ClientCapability capability : sorted(RESERVED_CAPABILITIES)) {
            disabled.add(reservedItem(capability));
        }
        return new CapabilityNegotiationResult(protocolVersion, enabled, disabled, rejected);
    }

    private CapabilityNegotiationItem reservedItem(ClientCapability capability) {
        return new CapabilityNegotiationItem(
                capability.name(),
                CapabilityErrorCode.CAPABILITY_RESERVED_FOR_FUTURE,
                "Capability is reserved for a future SyncForge protocol.");
    }

    private List<ClientCapability> sorted(Set<ClientCapability> capabilities) {
        return capabilities.stream()
                .sorted(Comparator.comparing(Enum::ordinal))
                .toList();
    }
}
