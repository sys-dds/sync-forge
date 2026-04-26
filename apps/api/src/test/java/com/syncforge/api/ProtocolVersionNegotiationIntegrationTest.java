package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import com.syncforge.api.protocol.ProtocolErrorCode;
import com.syncforge.api.protocol.ProtocolVersionNegotiationResult;
import com.syncforge.api.protocol.ProtocolVersionNegotiationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ProtocolVersionNegotiationIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    ProtocolVersionNegotiationService negotiationService;

    @Test
    void supportedVersionsAreAccepted() {
        ProtocolVersionNegotiationResult v1 = negotiationService.negotiate(Map.of("protocolVersion", 1));
        ProtocolVersionNegotiationResult v2 = negotiationService.negotiate(Map.of("protocolVersion", 2));

        assertThat(v1.accepted()).isTrue();
        assertThat(v1.negotiatedProtocolVersion()).isEqualTo(1);
        assertThat(v1.serverPreferredProtocolVersion()).isEqualTo(2);
        assertThat(v1.minimumSupportedProtocolVersion()).isEqualTo(1);
        assertThat(v1.maximumSupportedProtocolVersion()).isEqualTo(2);
        assertThat(v1.legacyDefaultApplied()).isFalse();
        assertThat(v2.accepted()).isTrue();
        assertThat(v2.negotiatedProtocolVersion()).isEqualTo(2);
    }

    @Test
    void missingVersionDefaultsToLegacyV1Explicitly() {
        ProtocolVersionNegotiationResult result = negotiationService.negotiate(Map.of());

        assertThat(result.accepted()).isTrue();
        assertThat(result.requestedProtocolVersion()).isNull();
        assertThat(result.negotiatedProtocolVersion()).isEqualTo(1);
        assertThat(result.legacyDefaultApplied()).isTrue();
    }

    @Test
    void unsupportedOldAndFutureVersionsAreRejectedWithMachineReadableCode() {
        ProtocolVersionNegotiationResult oldVersion = negotiationService.negotiate(Map.of("protocolVersion", 0));
        ProtocolVersionNegotiationResult futureVersion = negotiationService.negotiate(Map.of("protocolVersion", 99));

        assertThat(oldVersion.accepted()).isFalse();
        assertThat(oldVersion.rejectionCode()).isEqualTo(ProtocolErrorCode.UNSUPPORTED_PROTOCOL_VERSION);
        assertThat(oldVersion.rejectionReason()).contains("0");
        assertThat(futureVersion.accepted()).isFalse();
        assertThat(futureVersion.rejectionCode()).isEqualTo(ProtocolErrorCode.UNSUPPORTED_PROTOCOL_VERSION);
        assertThat(futureVersion.rejectionReason()).contains("99");
    }

    @Test
    void malformedVersionIsRejectedWithMachineReadableCode() {
        assertInvalidVersion(Map.of("protocolVersion", "2"));
        assertInvalidVersion(Map.of("protocolVersion", "nope"));
        assertInvalidVersion(new java.util.HashMap<>() {{
            put("protocolVersion", null);
        }});
        assertInvalidVersion(Map.of("protocolVersion", 1.5d));
        assertInvalidVersion(Map.of("protocolVersion", true));
    }

    private void assertInvalidVersion(Map<String, Object> fields) {
        ProtocolVersionNegotiationResult result = negotiationService.negotiate(fields);

        assertThat(result.accepted()).isFalse();
        assertThat(result.rejectionCode()).isEqualTo(ProtocolErrorCode.INVALID_PROTOCOL_VERSION);
        assertThat(result.rejectionReason()).isEqualTo("protocolVersion must be an integer.");
    }
}
