package com.flowcortex.user.api;

import java.time.Instant;

/**
 * What a successful authentication hands to the web layer: the access token for the body and the
 * refresh token for the cookie. {@code persistent} says whether the cookie outlives the browser
 * session (remember-me); {@code refreshTokenExpiresAt} is the session's absolute end.
 */
public record AuthSession(String accessToken, long expiresIn, String refreshToken, Instant refreshTokenExpiresAt,
        boolean persistent) {

    public AccessTokenResponse toResponse() {
        return new AccessTokenResponse(accessToken, expiresIn);
    }

    /** Keeps both tokens out of logs and exception messages. */
    @Override
    public String toString() {
        return "AuthSession[accessToken=***, expiresIn=" + expiresIn + ", refreshToken=***, refreshTokenExpiresAt="
                + refreshTokenExpiresAt + ", persistent=" + persistent + "]";
    }
}
