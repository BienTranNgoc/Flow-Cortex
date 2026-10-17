package com.flowcortex.user.application;

/**
 * Verified Google id_token claims that the user module cares about. Other
 * claims (locale, picture, name) are not retained.
 */
public record GoogleIdTokenClaims(String sub, String email, boolean emailVerified) {
}
