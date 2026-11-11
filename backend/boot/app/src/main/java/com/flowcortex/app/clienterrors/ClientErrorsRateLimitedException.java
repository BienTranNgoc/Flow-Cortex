package com.flowcortex.app.clienterrors;

import java.time.Duration;

/** The address spent its intake budget; {@code Retry-After} is whole seconds, at least 1. */
class ClientErrorsRateLimitedException extends ClientErrorRefusedException {

    private final long retryAfterSeconds;

    ClientErrorsRateLimitedException(Duration retryAfter) {
        super(ClientErrorRefusal.RATE_LIMITED);
        this.retryAfterSeconds = Math.max(1, (retryAfter.toNanos() + 999_999_999L) / 1_000_000_000L);
    }

    long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
