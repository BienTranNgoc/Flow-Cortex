package com.flowcortex.media.api;

import java.time.Instant;

/** One input bound to a run, with a signed URL to the run's own stored copy valid until {@code expiresAt}. */
public record RunInput(String bindingKey, String kind, String mimeType, String url, Instant expiresAt) {

    /** Without the URL: it grants the download to whoever holds it until it expires. */
    @Override
    public String toString() {
        return "RunInput[bindingKey=" + bindingKey + ", kind=" + kind + ", mimeType=" + mimeType
                + ", url=<redacted>, expiresAt=" + expiresAt + "]";
    }
}
