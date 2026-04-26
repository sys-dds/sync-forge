package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.syncforge.api.capability.CapabilityErrorCode;
import com.syncforge.api.capability.CapabilityNegotiationItem;
import com.syncforge.api.capability.CapabilityNegotiationResult;
import com.syncforge.api.capability.ClientCapability;
import com.syncforge.api.capability.ClientCapabilityService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ClientCapabilityNegotiationIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    ClientCapabilityService capabilityService;

    @Test
    void v1DefaultsEnableExistingRealtimeCapabilitiesAndDisableFutureCapabilities() {
        CapabilityNegotiationResult result = capabilityService.negotiate(1, List.of());

        assertThat(result.enabledCapabilities())
                .containsExactly(
                        ClientCapability.OPERATIONS,
                        ClientCapability.AWARENESS,
                        ClientCapability.PRESENCE,
                        ClientCapability.RESUME,
                        ClientCapability.BACKFILL,
                        ClientCapability.SNAPSHOT);
        assertThat(result.disabledCapabilities())
                .extracting(CapabilityNegotiationItem::capability)
                .containsExactly("OFFLINE_EDITS", "CAUSAL_DEPENDENCIES", "CRDT_TEXT_PREVIEW", "COMPRESSION");
        assertThat(result.rejectedCapabilities()).isEmpty();
    }

    @Test
    void v2EnablesOnlyRequestedActiveCapabilitiesAndReturnsDisabledCapabilities() {
        CapabilityNegotiationResult result = capabilityService.negotiate(2, List.of("OPERATIONS", "SNAPSHOT"));

        assertThat(result.enabledCapabilities()).containsExactly(ClientCapability.OPERATIONS, ClientCapability.SNAPSHOT);
        assertThat(result.disabledCapabilities())
                .extracting(CapabilityNegotiationItem::capability)
                .contains("AWARENESS", "PRESENCE", "RESUME", "BACKFILL");
        assertThat(result.disabledCapabilities())
                .filteredOn(item -> "AWARENESS".equals(item.capability()))
                .extracting(CapabilityNegotiationItem::code)
                .containsExactly(CapabilityErrorCode.CAPABILITY_NOT_NEGOTIATED);
    }

    @Test
    void reservedFutureCapabilitiesAreDisabledAndNeverEnabled() {
        CapabilityNegotiationResult result = capabilityService.negotiate(2, List.of(
                "OFFLINE_EDITS",
                "CAUSAL_DEPENDENCIES",
                "CRDT_TEXT_PREVIEW",
                "COMPRESSION"));

        assertThat(result.enabledCapabilities()).doesNotContain(
                ClientCapability.OFFLINE_EDITS,
                ClientCapability.CAUSAL_DEPENDENCIES,
                ClientCapability.CRDT_TEXT_PREVIEW,
                ClientCapability.COMPRESSION);
        assertThat(result.disabledCapabilities())
                .filteredOn(item -> item.code().equals(CapabilityErrorCode.CAPABILITY_RESERVED_FOR_FUTURE))
                .extracting(CapabilityNegotiationItem::capability)
                .containsExactly("OFFLINE_EDITS", "CAUSAL_DEPENDENCIES", "CRDT_TEXT_PREVIEW", "COMPRESSION");
    }

    @Test
    void unknownCapabilitiesAreRejectedAndNotSilentlyEnabled() {
        CapabilityNegotiationResult result = capabilityService.negotiate(2, List.of("OPERATIONS", "MYSTERY_MODE"));

        assertThat(result.enabledCapabilities()).containsExactly(ClientCapability.OPERATIONS);
        assertThat(result.rejectedCapabilities())
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.capability()).isEqualTo("MYSTERY_MODE");
                    assertThat(item.code()).isEqualTo(CapabilityErrorCode.UNKNOWN_CAPABILITY);
                });
    }
}
