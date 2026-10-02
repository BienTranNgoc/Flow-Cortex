package com.flowcortex.user.application;

import com.flowcortex.shared.security.RoleClaimJwtConverter;

/**
 * Claim name(s) this module mints into the JWT ({@link AuthServiceImpl}). The role claim is the
 * one {@link RoleClaimJwtConverter} reads back on every chain, so its name is taken from there.
 */
public final class JwtClaims {

    /** Single string claim carrying the domain {@code Role} enum name (e.g. {@code "ADMIN"}). */
    public static final String ROLE = RoleClaimJwtConverter.ROLE_CLAIM;

    private JwtClaims() {
    }
}
