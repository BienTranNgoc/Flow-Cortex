package com.flowcortex.user.apicall;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/** One recorded token write: who, which token, what route and how it ended. Never holds a query or body. */
record ApiTokenCall(UUID userId, UUID tokenId, String method, String route, Outcome outcome, int httpStatus,
        String errorCode, long durationMs, Instant occurredAt) {

    enum Outcome {
        OK, ERROR, BLOCKED, REPLAYED;

        String column() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
