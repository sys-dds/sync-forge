package com.syncforge.api.protocol;

import java.util.Map;

import org.springframework.stereotype.Service;

@Service
public class ProtocolVersionNegotiationService {
    public static final String PROTOCOL_VERSION_FIELD = "protocolVersion";

    private final ProtocolProperties properties;

    public ProtocolVersionNegotiationService(ProtocolProperties properties) {
        this.properties = properties;
    }

    public ProtocolVersionNegotiationResult negotiate(Map<String, Object> fields) {
        if (fields == null || !fields.containsKey(PROTOCOL_VERSION_FIELD)) {
            if (properties.allowMissingProtocolAsLegacy()) {
                return ProtocolVersionNegotiationResult.accepted(
                        null,
                        properties.legacyDefaultVersion(),
                        properties,
                        true);
            }
            return ProtocolVersionNegotiationResult.rejected(
                    null,
                    properties,
                    ProtocolErrorCode.MISSING_PROTOCOL_VERSION,
                    "protocolVersion is required.");
        }

        Integer requestedVersion = parseVersion(fields.get(PROTOCOL_VERSION_FIELD));
        if (requestedVersion == null) {
            return ProtocolVersionNegotiationResult.rejected(
                    null,
                    properties,
                    ProtocolErrorCode.INVALID_PROTOCOL_VERSION,
                    "protocolVersion must be an integer.");
        }
        if (requestedVersion < properties.minimumSupportedVersion()) {
            return ProtocolVersionNegotiationResult.rejected(
                    requestedVersion,
                    properties,
                    ProtocolErrorCode.UNSUPPORTED_PROTOCOL_VERSION,
                    "Protocol version " + requestedVersion + " is not supported.");
        }
        if (requestedVersion > properties.maximumSupportedVersion()) {
            return ProtocolVersionNegotiationResult.rejected(
                    requestedVersion,
                    properties,
                    ProtocolErrorCode.UNSUPPORTED_PROTOCOL_VERSION,
                    "Protocol version " + requestedVersion + " is not supported.");
        }
        return ProtocolVersionNegotiationResult.accepted(requestedVersion, requestedVersion, properties, false);
    }

    private Integer parseVersion(Object value) {
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            long version = ((Number) value).longValue();
            if (version < Integer.MIN_VALUE || version > Integer.MAX_VALUE) {
                return null;
            }
            return (int) version;
        }
        return null;
    }
}
