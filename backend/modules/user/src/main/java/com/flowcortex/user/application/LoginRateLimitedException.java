package com.flowcortex.user.application;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/** 429 Problem Detail with a {@code Retry-After} header in whole seconds (at least 1). */
public class LoginRateLimitedException extends LocalizedStatusException {

    private final long retryAfterSeconds;

    public LoginRateLimitedException(Duration retryAfter) {
        super(HttpStatus.TOO_MANY_REQUESTS, new LocalizedMessage("auth.rateLimited.login"));
        long nanos = retryAfter.toNanos();
        this.retryAfterSeconds = Math.max(1, (nanos + 999_999_999L) / 1_000_000_000L);
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
