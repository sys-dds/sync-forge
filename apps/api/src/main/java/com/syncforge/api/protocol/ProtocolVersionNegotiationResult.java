package com.syncforge.api.protocol;

public record ProtocolVersionNegotiationResult(
        boolean accepted,
        Integer requestedProtocolVersion,
        Integer negotiatedProtocolVersion,
        int serverPreferredProtocolVersion,
        int minimumSupportedProtocolVersion,
        int maximumSupportedProtocolVersion,
        boolean legacyDefaultApplied,
        String rejectionCode,
        String rejectionReason
) {
    public static ProtocolVersionNegotiationResult accepted(
            Integer requestedProtocolVersion,
            int negotiatedProtocolVersion,
            ProtocolProperties properties,
            boolean legacyDefaultApplied) {
        return new ProtocolVersionNegotiationResult(
                true,
                requestedProtocolVersion,
                negotiatedProtocolVersion,
                properties.currentVersion(),
                properties.minimumSupportedVersion(),
                properties.maximumSupportedVersion(),
                legacyDefaultApplied,
                null,
                null);
    }

    public static ProtocolVersionNegotiationResult rejected(
            Integer requestedProtocolVersion,
            ProtocolProperties properties,
            String rejectionCode,
            String rejectionReason) {
        return new ProtocolVersionNegotiationResult(
                false,
                requestedProtocolVersion,
                null,
                properties.currentVersion(),
                properties.minimumSupportedVersion(),
                properties.maximumSupportedVersion(),
                false,
                rejectionCode,
                rejectionReason);
    }
}
