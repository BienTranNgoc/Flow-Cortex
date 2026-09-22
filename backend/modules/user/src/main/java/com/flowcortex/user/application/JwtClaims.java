package com.flowcortex.user.application;

/**
 * Claim name(s) this module mints into the JWT ({@link AuthServiceImpl}) and
 * reads back out of it ({@code com.flowcortex.user.web.UserModuleSecurityConfig}'s
 * granted-authorities converter) — the two sides of the same contract, kept
 * in one place rather than as a string literal duplicated in both packages.
 */
public final class JwtClaims {

    /** Single string claim carrying the domain {@code Role} enum name (e.g. {@code "ADMIN"}). */
    public static final String ROLE = "role";

    private JwtClaims() {
    }
}
