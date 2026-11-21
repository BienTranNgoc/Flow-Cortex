package com.flowcortex.workflow.application;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/** 503 Problem Detail for a waveform that cannot be served right now, with {@code Retry-After: 10}. */
final class PeaksRetryLaterException extends LocalizedStatusException {

    static final int RETRY_AFTER_SECONDS = 10;

    private PeaksRetryLaterException(LocalizedMessage detail) {
        super(HttpStatus.SERVICE_UNAVAILABLE, detail);
    }

    /** Every decode slot of this instance stayed taken for the whole wait. */
    static PeaksRetryLaterException busy() {
        return new PeaksRetryLaterException(new LocalizedMessage("workflow.peaks.busy"));
    }

    /** The store could not serve the sound, or its read failed part-way. */
    static PeaksRetryLaterException unavailable() {
        return new PeaksRetryLaterException(new LocalizedMessage("workflow.peaks.unavailable"));
    }

    @Override
    public HttpHeaders getHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, Integer.toString(RETRY_AFTER_SECONDS));
        return headers;
    }
}
