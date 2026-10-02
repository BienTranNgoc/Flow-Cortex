package com.flowcortex.media.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A signed URL the caller PUTs one file to, with exactly {@code headers}; the object lands at a
 * key the server chose, and {@code uploadId} names it when a run is started.
 */
public record UploadGrant(UUID uploadId, String url, String method, Map<String, String> headers, Instant expiresAt,
        long maxBytes) {

    /** Without the URL: it grants the upload to whoever holds it until it expires. */
    @Override
    public String toString() {
        return "UploadGrant[uploadId=" + uploadId + ", url=<redacted>, method=" + method + ", headers=" + headers
                + ", expiresAt=" + expiresAt + ", maxBytes=" + maxBytes + "]";
    }
}
