package com.flowcortex.billing.refund;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/** 429 Problem Detail with a {@code Retry-After} header in whole seconds (at least 1). */
class RefundRateLimitedException extends LocalizedStatusException {

    private final long retryAfterSeconds;

    private RefundRateLimitedException(long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS, new LocalizedMessage("billing.refundRateLimited"));
        this.retryAfterSeconds = retryAfterSeconds;
    }

    static RefundRateLimitedException of(Duration wait) {
        long nanos = wait.toNanos();
        return new RefundRateLimitedException(Math.max(1, (nanos + 999_999_999L) / 1_000_000_000L));
    }

    long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    @Override
    public HttpHeaders getHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        return headers;
    }
}
