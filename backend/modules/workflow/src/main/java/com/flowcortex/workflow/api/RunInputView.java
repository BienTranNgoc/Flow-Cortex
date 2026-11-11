package com.flowcortex.workflow.api;

import java.time.Instant;

/** One input a run was started with, with a time-limited URL to the run's stored copy. */
public record RunInputView(String bindingKey, String kind, String mimeType, String url, Instant expiresAt) {

    /** Without the URL: it grants the download to whoever holds it until it expires. */
    @Override
    public String toString() {
        return "RunInputView[bindingKey=" + bindingKey + ", kind=" + kind + ", mimeType=" + mimeType
                + ", url=<redacted>, expiresAt=" + expiresAt + "]";
    }
}
