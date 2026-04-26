package com.syncforge.api.awareness.api;

import java.time.OffsetDateTime;

public record ExpireAwarenessRequest(OffsetDateTime now) {
}
