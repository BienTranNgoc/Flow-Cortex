package com.flowcortex.user.application;

import java.time.Instant;

/**
 * Pair of access + refresh tokens returned by every successful login. Both
 * expiries are absolute {@link Instant}s so the client never has to interpret
 * TTLs.
 */
public record TokenPair(String accessToken, String refreshToken, Instant accessTokenExpiresAt,
        Instant refreshTokenExpiresAt) {
}
