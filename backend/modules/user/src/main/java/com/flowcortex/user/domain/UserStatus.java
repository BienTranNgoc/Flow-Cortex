package com.flowcortex.user.domain;

/**
 * Lifecycle status of an {@link AppUser} (design.md D1). A {@code SUSPENDED}
 * user is refused at login (403, design.md D4/R-023) AND on every subsequent
 * authenticated request (per-request status re-check in {@code
 * UserDirectoryImpl}/{@code AuthServiceImpl}, R-021) — a token already issued
 * before the suspension is not additionally revoked (there is no session
 * store to revoke it from, design.md scope — this change mints stateless
 * JWTs only), but its bearer is refused DB-side the moment their row flips to
 * {@code SUSPENDED}.
 */
public enum UserStatus {
    ACTIVE,
    SUSPENDED
}
