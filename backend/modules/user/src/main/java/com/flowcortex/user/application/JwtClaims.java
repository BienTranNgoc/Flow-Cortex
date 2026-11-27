package com.flowcortex.user.application;

import com.flowcortex.shared.security.RoleClaimJwtConverter;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Claim name(s) this module mints into the JWT ({@link AuthServiceImpl}). The role claim is the
 * one {@link RoleClaimJwtConverter} reads back on every chain, so its name is taken from there.
 */
public final class JwtClaims {

    /** Single string claim carrying the domain {@code Role} enum name (e.g. {@code "ADMIN"}). */
    public static final String ROLE = RoleClaimJwtConverter.ROLE_CLAIM;

    /** Boolean claim present (true) only on an access token whose session passed a TOTP or recovery code. */
    public static final String MFA = "mfa";

    private JwtClaims() {
    }

    /** Whether the token's session passed a second factor; a token without the claim reads as false. */
    public static boolean mfaOf(Jwt jwt) {
        return jwt != null && Boolean.TRUE.equals(jwt.getClaim(MFA));
    }
}
