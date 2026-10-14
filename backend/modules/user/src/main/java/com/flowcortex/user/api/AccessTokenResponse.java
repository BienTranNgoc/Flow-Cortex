package com.flowcortex.user.api;

/**
 * The only token a client ever sees in a response body; the refresh token travels in an HttpOnly
 * cookie. {@code expiresIn} is the access token's lifetime in seconds.
 */
public record AccessTokenResponse(String accessToken, long expiresIn) {
}
