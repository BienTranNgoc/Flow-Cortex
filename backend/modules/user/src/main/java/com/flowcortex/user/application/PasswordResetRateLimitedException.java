package com.flowcortex.user.application;

import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 429 Problem Detail with a {@code Retry-After} header in whole seconds (at least 1), repeated as the
 * {@code retryAfterSeconds} body property for clients that cannot read response headers.
 */
public class PasswordResetRateLimitedException extends ResponseStatusException {

    private final long retryAfterSeconds;

    public PasswordResetRateLimitedException(Duration retryAfter) {
        super(HttpStatus.TOO_MANY_REQUESTS, "Too many reset requests. Try again later.");
        long nanos = retryAfter.toNanos();
        this.retryAfterSeconds = Math.max(1, (nanos + 999_999_999L) / 1_000_000_000L);
        getBody().setProperty("retryAfterSeconds", retryAfterSeconds);
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    @Override
    public HttpHeaders getHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        return headers;
    }
}
