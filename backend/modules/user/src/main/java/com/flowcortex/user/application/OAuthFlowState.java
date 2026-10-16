package com.flowcortex.user.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * Everything one Google sign-in attempt must remember between {@code /authorize} and {@code /callback}.
 * It lives in a sealed cookie ({@link OAuthFlowCookieCodec}), so the attempt is bound to the browser
 * that started it and no server-side store is needed.
 */
public record OAuthFlowState(String state, String codeVerifier, String nonce, String returnTo, boolean rememberMe,
        Instant expiresAt) {

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    public static OAuthFlowState begin(String returnTo, boolean rememberMe, Duration ttl, Clock clock) {
        return new OAuthFlowState(randomToken(), randomToken(), randomToken(), returnTo, rememberMe,
                Instant.now(clock).plus(ttl));
    }

    /** The PKCE {@code code_challenge} (S256) for {@link #codeVerifier()}. */
    public String codeChallenge() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return URL_ENCODER.encodeToString(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return URL_ENCODER.encodeToString(bytes);
    }
}
