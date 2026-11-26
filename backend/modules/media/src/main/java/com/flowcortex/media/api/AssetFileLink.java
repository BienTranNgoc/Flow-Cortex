package com.flowcortex.media.api;

import java.time.Instant;

/** A signed URL that serves one stored asset file until {@code expiresAt}. */
public record AssetFileLink(String url, Instant expiresAt) {

    /** Without the URL: it grants the download to whoever holds it until it expires. */
    @Override
    public String toString() {
        return "AssetFileLink[url=<redacted>, expiresAt=" + expiresAt + "]";
    }
}
