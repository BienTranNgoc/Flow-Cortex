package com.flowcortex.user.oauth;

import org.springframework.http.HttpStatus;

/**
 * An RFC 6749 section 5.2 / RFC 7591 error of the machine endpoints. {@link OAuthErrorAdvice} renders
 * it as JSON {@code {error, error_description}}; the description never carries a request value.
 */
class OAuthException extends RuntimeException {

    private final HttpStatus status;
    private final String error;
    private final boolean basicChallenge;
    private final long retryAfterSeconds;

    private OAuthException(HttpStatus status, String error, String description, boolean basicChallenge,
            long retryAfterSeconds) {
        super(description);
        this.status = status;
        this.error = error;
        this.basicChallenge = basicChallenge;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    static OAuthException of(String error, String description) {
        return new OAuthException(HttpStatus.BAD_REQUEST, error, description, false, 0);
    }

    /** 401; {@code viaBasic} adds the {@code WWW-Authenticate} challenge the client attempted. */
    static OAuthException invalidClient(boolean viaBasic) {
        return new OAuthException(HttpStatus.UNAUTHORIZED, "invalid_client", "Client authentication failed", viaBasic,
                0);
    }

    static OAuthException throttled(long retryAfterSeconds) {
        return new OAuthException(HttpStatus.TOO_MANY_REQUESTS, "temporarily_unavailable",
                "Too many requests from this address. Try again shortly.", false, Math.max(1, retryAfterSeconds));
    }

    HttpStatus status() {
        return status;
    }

    String error() {
        return error;
    }

    boolean basicChallenge() {
        return basicChallenge;
    }

    long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
