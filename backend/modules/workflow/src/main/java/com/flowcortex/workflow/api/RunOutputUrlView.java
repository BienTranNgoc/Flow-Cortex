package com.flowcortex.workflow.api;

import java.time.Instant;

/** A time-limited download URL for one output of a run step. */
public record RunOutputUrlView(String url, Instant expiresAt) {

    /** Without the URL: it grants the download to whoever holds it until it expires. */
    @Override
    public String toString() {
        return "RunOutputUrlView[url=<redacted>, expiresAt=" + expiresAt + "]";
    }
}
