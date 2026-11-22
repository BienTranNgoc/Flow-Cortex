package com.flowcortex.media.api;

import java.util.OptionalLong;

/** Existence check and signed links for the files of library assets, in the media bucket only. */
public interface AssetFiles {

    /** How the browser treats the response: saved to disk, or shown in the page. */
    enum Disposition {
        ATTACHMENT("attachment"),
        INLINE("inline");

        private final String token;

        Disposition(String token) {
            this.token = token;
        }

        public String token() {
            return token;
        }
    }

    /**
     * The stored object's size in bytes, or empty when there is no object at {@code key}.
     * @throws MediaStoreUnavailableException when the object store fails in any other way, a 403 included
     * @throws IllegalArgumentException when {@code bucket} is not the media bucket
     */
    OptionalLong probe(String bucket, String key);

    /**
     * A link valid for the media presign lifetime whose response carries {@code disposition} and
     * {@code fileName} as an RFC 6266 {@code filename*} in UTF-8 with an ASCII {@code filename} fallback.
     * The response's content type is the one the object was stored with; {@code mimeType} is the asset's
     * recorded type, kept for the issue log.
     * @throws IllegalArgumentException when {@code bucket} is not the media bucket or {@code fileName} is blank
     */
    AssetFileLink sign(String bucket, String key, Disposition disposition, String fileName, String mimeType);
}
