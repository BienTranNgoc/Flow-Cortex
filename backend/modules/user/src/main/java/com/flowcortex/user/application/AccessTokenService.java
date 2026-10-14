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
 * Access tokens are stateless: once issued one stays valid until it expires.
 */
@Service
public class AccessTokenService {

    private static final String ISSUER = "flow-cortex";

    private final JwtEncoder jwtEncoder;
    private final AccessTokenProperties properties;
    private final Clock clock;

    public AccessTokenService(JwtEncoder jwtEncoder, AccessTokenProperties properties, Clock clock) {
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
        this.clock = clock;
    }

    /** Mint a fresh access JWT for the user, carrying the user's id (subject) and role. */
    public String mint(AppUser user) {
        Instant now = Instant.now(clock);
        Instant expiresAt = now.plus(properties.accessTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .issuedAt(now)
                .expiresAt(expiresAt)
                .subject(user.getId().toString())
                .claim(JwtClaims.ROLE, user.getRole().name())
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    public Duration accessTokenTtl() {
        return properties.accessTokenTtl();
    }
}
