package com.flowcortex.user.stepup;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/** 429 with code {@code STEP_UP_RATE_LIMITED} and a {@code Retry-After} header in whole seconds (at least 1). */
class StepUpRateLimitedException extends LocalizedStatusException {

    private final long retryAfterSeconds;

    StepUpRateLimitedException(Duration retryAfter) {
        super(HttpStatus.TOO_MANY_REQUESTS, new LocalizedMessage("stepUp.rateLimited"));
        this.retryAfterSeconds = Math.max(1, (retryAfter.toNanos() + 999_999_999L) / 1_000_000_000L);
        getBody().setProperty("code", "STEP_UP_RATE_LIMITED");
        getBody().setProperty("retryAfterSeconds", retryAfterSeconds);
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
