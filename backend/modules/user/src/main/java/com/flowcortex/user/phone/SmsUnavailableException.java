package com.flowcortex.user.phone;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import java.net.URI;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/**
 * 503 Problem Detail ({@code code} {@code SMS_UNAVAILABLE}) for a code the carrier did not take,
 * with when to ask again as the {@code retryAfterSeconds} property and the {@code Retry-After} header.
 */
class SmsUnavailableException extends LocalizedStatusException {

    static final String CODE = "SMS_UNAVAILABLE";

    private static final URI TYPE = URI.create("https://flow-cortex.dev/problems/service-unavailable");

    private final long retryAfterSeconds;

    SmsUnavailableException(long retryAfterSeconds) {
        super(HttpStatus.SERVICE_UNAVAILABLE, new LocalizedMessage("phone.send.smsUnavailable"));
        this.retryAfterSeconds = retryAfterSeconds;
        setType(TYPE);
        getBody().setProperty("code", CODE);
        getBody().setProperty("retryAfterSeconds", retryAfterSeconds);
    }

    @Override
    public HttpHeaders getHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        return headers;
    }
}
