package com.flowcortex.media.api;

import java.time.Instant;

/** A signed URL that downloads one stored output until {@code expiresAt}. */
public record MediaDownload(String url, Instant expiresAt) {

    /** Without the URL: it grants the download to whoever holds it until it expires. */
    @Override
    public String toString() {
        return "MediaDownload[url=<redacted>, expiresAt=" + expiresAt + "]";
    }
}
