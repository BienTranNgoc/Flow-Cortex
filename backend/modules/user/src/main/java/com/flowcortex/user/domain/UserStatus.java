package com.flowcortex.user.domain;

/**
 * Lifecycle status of an {@link AppUser}. {@code SUSPENDED} is refused at login (403) and on
 * every later request (per-request DB re-check) — an already-issued JWT is not separately
 * revoked (no session store; tokens are stateless), but its bearer is refused the moment the
 * row flips to {@code SUSPENDED}.
 */
public enum UserStatus {
    ACTIVE,
    SUSPENDED
}
