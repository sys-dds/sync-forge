package com.syncforge.api.protocol;

import org.springframework.stereotype.Component;

@Component
public class ProtocolProperties {
    public int minimumSupportedVersion() {
        return 1;
    }

    public int currentVersion() {
        return 2;
    }

    public int maximumSupportedVersion() {
        return 2;
    }

    public int legacyDefaultVersion() {
        return 1;
    }

    public boolean allowMissingProtocolAsLegacy() {
        return true;
    }
}
