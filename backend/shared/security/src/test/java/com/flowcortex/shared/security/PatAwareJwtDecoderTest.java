package com.flowcortex.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

class PatAwareJwtDecoderTest {

    private static final SecretKey KEY = new SecretKeySpec(
            "a-valid-jwt-signing-secret-of-32-bytes-or-more".getBytes(StandardCharsets.UTF_8), "HmacSHA256");

    private final JwtDecoder delegate = spy();
    private final PersonalAccessTokenAuthenticator authenticator = mock(PersonalAccessTokenAuthenticator.class);

    private static JwtDecoder spy() {
        return org.mockito.Mockito.spy(NimbusJwtDecoder.withSecretKey(KEY).macAlgorithm(MacAlgorithm.HS256).build());
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<PersonalAccessTokenAuthenticator> providerOf(
            PersonalAccessTokenAuthenticator authenticator) {
        ObjectProvider<PersonalAccessTokenAuthenticator> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(authenticator);
        return provider;
    }

    private static String signedToken() {
        JwtEncoder encoder = NimbusJwtEncoder.withSecretKey(KEY).build();
        Instant now = Instant.now();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
                JwtClaimsSet.builder().subject("user-1").issuedAt(now).expiresAt(now.plusSeconds(60)).build()))
                .getTokenValue();
    }

    private static Jwt patPrincipal() {
        Instant now = Instant.now();
        return Jwt.withTokenValue("pat").header("alg", "none").subject("user-1").issuedAt(now)
                .expiresAt(now.plusSeconds(60)).claim(PersonalAccessTokenAuthenticator.AUTH_CLAIM,
                        PersonalAccessTokenAuthenticator.AUTH_PAT).build();
    }

    @Test
    void anHs256SessionTokenDecodesThroughTheDelegateUnchanged() {
        PatAwareJwtDecoder decoder = new PatAwareJwtDecoder(delegate, providerOf(authenticator));

        Jwt jwt = decoder.decode(signedToken());

        assertThat(jwt.getSubject()).isEqualTo("user-1");
        verifyNoInteractions(authenticator);
    }

    @Test
    void aPatValueReturnsTheJwtTheAuthenticatorBuilt() {
        Jwt principal = patPrincipal();
        when(authenticator.authenticate("fc_pat_x")).thenReturn(Optional.of(principal));
        PatAwareJwtDecoder decoder = new PatAwareJwtDecoder(delegate, providerOf(authenticator));

        assertThat(decoder.decode("fc_pat_x")).isSameAs(principal);
        assertThat(PersonalAccessTokenAuthenticator.isPat(principal)).isTrue();
    }

    @Test
    void aPatValueTheAuthenticatorRefusesIsABadJwt() {
        when(authenticator.authenticate(anyString())).thenReturn(Optional.empty());
        PatAwareJwtDecoder decoder = new PatAwareJwtDecoder(delegate, providerOf(authenticator));

        assertThatThrownBy(() -> decoder.decode("fc_pat_x")).isInstanceOf(BadJwtException.class);
    }

    @Test
    void aPatValueWithNoAuthenticatorBeanIsABadJwt() {
        PatAwareJwtDecoder decoder = new PatAwareJwtDecoder(delegate, providerOf(null));

        assertThatThrownBy(() -> decoder.decode("fc_pat_x")).isInstanceOf(BadJwtException.class);
    }

    @Test
    void aPatValueNeverReachesTheDelegate() {
        when(authenticator.authenticate(anyString())).thenReturn(Optional.empty());
        PatAwareJwtDecoder decoder = new PatAwareJwtDecoder(delegate, providerOf(authenticator));

        assertThatThrownBy(() -> decoder.decode("fc_pat_x")).isInstanceOf(BadJwtException.class);

        verify(delegate, never()).decode(anyString());
    }

    @Test
    void aSessionJwtIsNotAPat() {
        PatAwareJwtDecoder decoder = new PatAwareJwtDecoder(delegate, providerOf(authenticator));

        assertThat(PersonalAccessTokenAuthenticator.isPat(decoder.decode(signedToken()))).isFalse();
    }
}
