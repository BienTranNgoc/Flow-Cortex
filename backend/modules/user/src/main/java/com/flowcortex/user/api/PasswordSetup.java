package com.flowcortex.user.api;

/**
 * A member's first password with the identity chosen alongside it. {@code setupToken} is the one-time
 * token a confirmation or set-password link handed out; {@code username} may be {@code null}.
 */
public record PasswordSetup(String setupToken, String password, String firstName, String lastName,
        String username) {
}
