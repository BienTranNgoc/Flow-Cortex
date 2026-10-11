package com.flowcortex.user.application;

import com.flowcortex.user.domain.AppUser;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Mints the access JWT. Extracted from {@code AuthServiceImpl.mintToken} so the
 * refresh-token service can call it without depending on the full auth service.
 * The {@code tv} (token version) claim is part of the JWT body; the shared
 * {@code RoleClaimJwtConverter} ignores it — only {@link #mint} writes it and
 * only logout's session-cached read enforces it.
 */
@Service
public class AccessTokenService {

    private static final String ISSUER = "flow-cortex";

    private static final String TV_CLAIM = "tv";

    private final JwtEncoder jwtEncoder;
    private final AccessTokenProperties properties;
    private final Clock clock;

    public AccessTokenService(JwtEncoder jwtEncoder, AccessTokenProperties properties, Clock clock) {
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Mint a fresh access JWT for the user. The token carries the user's id
     * (subject), role, and the current {@code tokenVersion} — the latter is
     * what the logout flow increments to invalidate issued access tokens.
     */
    public String mint(AppUser user) {
        Instant now = Instant.now(clock);
        Instant expiresAt = now.plus(properties.accessTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .issuedAt(now)
                .expiresAt(expiresAt)
                .subject(user.getId().toString())
                .claim(JwtClaims.ROLE, user.getRole().name())
                .claim(TV_CLAIM, user.getTokenVersion())
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    /** Read the {@code tv} claim from a token, or {@code 0} if absent. */
    public long readTokenVersion(String accessToken) {
        int lastDot = accessToken.lastIndexOf('.');
        if (lastDot < 0 || lastDot == accessToken.length() - 1) {
            return 0L;
        }
        String payload = accessToken.substring(lastDot + 1);
        int tvIdx = payload.indexOf("\"tv\":");
        if (tvIdx < 0) {
            return 0L;
        }
        int after = tvIdx + 5;
        int end = after;
        while (end < payload.length() && (Character.isDigit(payload.charAt(end)) || payload.charAt(end) == '-')) {
            end++;
        }
        if (end == after) {
            return 0L;
        }
        try {
            return Long.parseLong(payload.substring(after, end));
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    public Duration accessTokenTtl() {
        return properties.accessTokenTtl();
    }
}
