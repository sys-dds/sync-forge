package com.syncforge.api.capability;

import com.syncforge.api.protocol.ProtocolSessionRepository;
import org.springframework.stereotype.Service;

@Service
public class CapabilityGateService {
    private final ProtocolSessionRepository protocolSessionRepository;

    public CapabilityGateService(ProtocolSessionRepository protocolSessionRepository) {
        this.protocolSessionRepository = protocolSessionRepository;
    }

    public CapabilityGateResult require(String connectionId, ClientCapability capability) {
        return protocolSessionRepository.findActiveByConnectionId(connectionId)
                .map(session -> session.enabledCapabilities().contains(capability)
                        ? CapabilityGateResult.allow()
                        : CapabilityGateResult.rejected(codeFor(capability), capability.name() + " was not negotiated."))
                .orElseGet(() -> CapabilityGateResult.rejected(
                        CapabilityErrorCode.NEGOTIATION_REQUIRED,
                        "Protocol negotiation is required before using this feature."));
    }

    public String codeFor(ClientCapability capability) {
        return switch (capability) {
            case OPERATIONS -> CapabilityErrorCode.OPERATIONS_NOT_NEGOTIATED;
            case AWARENESS -> CapabilityErrorCode.AWARENESS_NOT_NEGOTIATED;
            case RESUME -> CapabilityErrorCode.RESUME_NOT_NEGOTIATED;
            case BACKFILL -> CapabilityErrorCode.BACKFILL_NOT_NEGOTIATED;
            case SNAPSHOT -> CapabilityErrorCode.SNAPSHOT_NOT_NEGOTIATED;
            default -> CapabilityErrorCode.CAPABILITY_NOT_NEGOTIATED;
        };
    }
}
