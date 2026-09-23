package com.flowcortex.user.domain;

/**
 * Lifecycle status of an {@link AppUser}. A {@code SUSPENDED} user is refused
 * at login (403) AND on every subsequent authenticated request (per-request
 * status re-check in {@code UserDirectoryImpl}/{@code AuthServiceImpl}) — a
 * token already issued before the suspension is not additionally revoked
 * (there is no session store to revoke it from — this module mints stateless
 * JWTs only), but its bearer is refused DB-side the moment their row flips to
 * {@code SUSPENDED}.
 */
public enum UserStatus {
    ACTIVE,
    SUSPENDED
}
