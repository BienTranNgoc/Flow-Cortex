package com.flowcortex.user.api;

import java.time.Instant;

/**
 * Public-facing shape of a token pair. Lives in {@code api/} so the web
 * layer and any future sibling module can return it without depending on the
 * internal {@code application} record.
 */
public record TokenPairResponse(String accessToken, String refreshToken, Instant accessTokenExpiresAt,
        Instant refreshTokenExpiresAt) {
}
