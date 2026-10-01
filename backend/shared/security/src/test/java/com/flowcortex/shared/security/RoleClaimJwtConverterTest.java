package com.flowcortex.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class RoleClaimJwtConverterTest {

    private final RoleClaimJwtConverter converter = new RoleClaimJwtConverter();

    @Test
    void theRoleClaimBecomesARolePrefixedAuthority() {
        String subject = UUID.randomUUID().toString();

        AbstractAuthenticationToken authentication = converter.convert(jwt(subject, "ADMIN"));

        assertThat(roles(authentication)).containsExactly("ROLE_ADMIN");
        assertThat(authentication.getName()).isEqualTo(subject);
    }

    @Test
    void theOnlyOtherAuthorityIsTheBearerFactorSpringSecurityAdds() {
        AbstractAuthenticationToken authentication = converter.convert(jwt("someone", "ADMIN"));

        assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_ADMIN", "FACTOR_BEARER");
    }

    @Test
    void aTokenWithoutTheRoleClaimHasNoAuthority() {
        AbstractAuthenticationToken authentication = converter.convert(jwt(UUID.randomUUID().toString(), null));

        assertThat(roles(authentication)).isEmpty();
    }

    @Test
    void scopesAreNotReadAsAuthorities() {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "HS256").subject("someone")
                .claim("scope", "ADMIN").claim(RoleClaimJwtConverter.ROLE_CLAIM, "MEMBER").build();

        assertThat(roles(converter.convert(jwt))).containsExactly("ROLE_MEMBER");
    }

    /** Spring Security 7 also grants a {@code FACTOR_BEARER} authority to every bearer token. */
    private static List<String> roles(AbstractAuthenticationToken authentication) {
        return authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .filter(authority -> !authority.startsWith("FACTOR_")).toList();
    }

    private static Jwt jwt(String subject, String role) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "HS256").subject(subject);
        if (role != null) {
            builder.claim(RoleClaimJwtConverter.ROLE_CLAIM, role);
        }
        return builder.build();
    }
}
