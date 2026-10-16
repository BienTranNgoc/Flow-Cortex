package com.flowcortex.user.phone;

import java.net.URI;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 429 Problem Detail naming why no code was sent ({@code code}: {@code OTP_COOLDOWN},
 * {@code OTP_HOURLY_LIMIT} or {@code OTP_DAILY_LIMIT}) and when to ask again, as the
 * {@code retryAfterSeconds} property and the {@code Retry-After} header.
 */
class OtpRateLimitedException extends ResponseStatusException {

    private static final URI TYPE = URI.create("https://flow-cortex.dev/problems/too-many-requests");

    private final long retryAfterSeconds;

    OtpRateLimitedException(String code, String detail, long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS, detail);
        this.retryAfterSeconds = retryAfterSeconds;
        setType(TYPE);
        getBody().setProperty("code", code);
        getBody().setProperty("retryAfterSeconds", retryAfterSeconds);
    }

    @Override
    public HttpHeaders getHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        return headers;
    }
}
