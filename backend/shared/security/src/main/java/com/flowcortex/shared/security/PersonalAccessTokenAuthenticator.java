package com.flowcortex.shared.security;

import java.util.Optional;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Turns a personal access token into the principal the resource-server chains already consume.
 * Implemented by the module that stores the tokens; {@link PatAwareJwtDecoder} calls it for every
 * bearer that starts with {@link #PREFIX}.
 */
public interface PersonalAccessTokenAuthenticator {

    /** Every personal access token starts with this, which is how the decoder and the throttle tell it apart. */
    String PREFIX = "fc_pat_";

    /** Claim marking a principal that came from a token rather than a signed session JWT. */
    String AUTH_CLAIM = "fc_auth";

    String AUTH_PAT = "pat";

    /** The owner's principal (no role claim), or empty when the token is unknown, dead or its owner ineligible. */
    Optional<Jwt> authenticate(String rawToken);

    static boolean isPat(Jwt jwt) {
        return jwt != null && AUTH_PAT.equals(jwt.getClaimAsString(AUTH_CLAIM));
    }
}
