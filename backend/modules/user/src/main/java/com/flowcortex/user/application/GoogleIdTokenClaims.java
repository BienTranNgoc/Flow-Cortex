package com.flowcortex.user.application;

/**
 * Verified Google id_token claims that the user module cares about. {@code name} is the display name
 * the token offers ({@code name}, else {@code given_name} and {@code family_name}), trimmed and
 * length-limited, or {@code null} when it offers none. Other claims (locale, picture) are not retained.
 */
public record GoogleIdTokenClaims(String sub, String email, boolean emailVerified, String name) {

    public GoogleIdTokenClaims(String sub, String email, boolean emailVerified) {
        this(sub, email, emailVerified, null);
    }
}
