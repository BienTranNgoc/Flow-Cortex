package com.flowcortex.profile.application;

import java.time.Instant;
import java.util.UUID;

/** A stored picture with a time-limited URL its owner can load it from. */
public record ProfileImageView(UUID id, String url, Instant expiresAt, int width, int height) {

    /** Without the URL: it grants the download to whoever holds it until it expires. */
    @Override
    public String toString() {
        return "ProfileImageView[id=" + id + ", url=<redacted>, expiresAt=" + expiresAt + ", width=" + width
                + ", height=" + height + "]";
    }
}
