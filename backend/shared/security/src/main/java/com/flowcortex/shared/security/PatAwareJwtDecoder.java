package com.flowcortex.shared.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * The one decoder every bearer chain shares: a {@code fc_pat_} value goes to the token
 * authenticator, anything else to the HS256 decoder unchanged. A refused token is a
 * {@link BadJwtException} (401); no other credential is tried for it.
 */
public final class PatAwareJwtDecoder implements JwtDecoder {

    private final JwtDecoder sessionDecoder;
    private final ObjectProvider<PersonalAccessTokenAuthenticator> authenticator;

    public PatAwareJwtDecoder(JwtDecoder sessionDecoder, ObjectProvider<PersonalAccessTokenAuthenticator> authenticator) {
        this.sessionDecoder = sessionDecoder;
        this.authenticator = authenticator;
    }

    @Override
    public Jwt decode(String token) throws JwtException {
        if (!token.startsWith(PersonalAccessTokenAuthenticator.PREFIX)) {
            return sessionDecoder.decode(token);
        }
        PersonalAccessTokenAuthenticator resolver = authenticator.getIfAvailable();
        if (resolver == null) {
            throw new BadJwtException("Personal access tokens are not available");
        }
        return resolver.authenticate(token).orElseThrow(() -> new BadJwtException("Invalid personal access token"));
    }
}
