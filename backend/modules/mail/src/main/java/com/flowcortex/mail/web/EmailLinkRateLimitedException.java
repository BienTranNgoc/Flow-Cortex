package com.flowcortex.mail.web;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/**
 * 429 Problem Detail with a {@code Retry-After} header in whole seconds (at least 1), repeated as the
 * {@code retryAfterSeconds} body property for clients that cannot read response headers.
 */
class EmailLinkRateLimitedException extends LocalizedStatusException {

    private final long retryAfterSeconds;

    EmailLinkRateLimitedException(Duration retryAfter) {
        super(HttpStatus.TOO_MANY_REQUESTS, new LocalizedMessage("mail.link.rateLimited"));
        this.retryAfterSeconds = Math.max(1, (retryAfter.toNanos() + 999_999_999L) / 1_000_000_000L);
        getBody().setProperty("retryAfterSeconds", retryAfterSeconds);
    }

    @Override
    public HttpHeaders getHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        return headers;
    }
}
