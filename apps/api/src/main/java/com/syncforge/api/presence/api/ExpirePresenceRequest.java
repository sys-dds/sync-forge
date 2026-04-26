package com.syncforge.api.presence.api;

import java.time.OffsetDateTime;

public record ExpirePresenceRequest(OffsetDateTime now) {
}
