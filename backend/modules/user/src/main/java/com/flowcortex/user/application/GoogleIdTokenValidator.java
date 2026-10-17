package com.flowcortex.user.application;

import java.util.List;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The claim checks OIDC Core §3.1.3.7 asks of a Google {@code id_token}: issuer, audience (our client
 * id), {@code azp} (our client id, required when there is more than one audience) and a verified
 * email. The nonce differs per sign-in, so {@link GoogleOAuthClient} checks it; expiry is checked by
 * the decoder's timestamp validator.
 */
final class GoogleIdTokenValidator implements OAuth2TokenValidator<Jwt> {

    private static final List<String> ISSUERS = List.of("https://accounts.google.com", "accounts.google.com");

    private final String clientId;

    GoogleIdTokenValidator(String clientId) {
        this.clientId = clientId;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        String issuer = jwt.getClaimAsString("iss");
        if (issuer == null || !ISSUERS.contains(issuer)) {
            return failure("id_token issuer mismatch");
        }
        List<String> audience = jwt.getAudience();
        if (audience == null || !audience.contains(clientId)) {
            return failure("id_token audience mismatch");
        }
        String authorizedParty = jwt.getClaimAsString("azp");
        if (authorizedParty == null ? audience.size() > 1 : !clientId.equals(authorizedParty)) {
            return failure("id_token azp mismatch");
        }
        if (!Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified"))) {
            return failure("id_token email is not verified");
        }
        return OAuth2TokenValidatorResult.success();
    }

    private static OAuth2TokenValidatorResult failure(String description) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", description, null));
    }
}
