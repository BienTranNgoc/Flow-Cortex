package com.flowcortex.shared.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/**
 * Maps the JWT's single {@value #ROLE_CLAIM} claim to a {@code ROLE_*} authority, so {@code
 * hasRole('ADMIN')} works. The default converter reads OAuth2 {@code scope}/{@code scp} instead.
 */
public final class RoleClaimJwtConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    /** The claim the user module mints the domain role name (e.g. {@code "ADMIN"}) into. */
    public static final String ROLE_CLAIM = "role";

    private final JwtAuthenticationConverter delegate = new JwtAuthenticationConverter();

    public RoleClaimJwtConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthorityPrefix("ROLE_");
        authorities.setAuthoritiesClaimName(ROLE_CLAIM);
        delegate.setJwtGrantedAuthoritiesConverter(authorities);
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        return delegate.convert(jwt);
    }
}
