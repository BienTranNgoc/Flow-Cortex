package com.flowcortex.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.user.domain.AppUser;
import com.flowcortex.user.domain.Role;
import com.flowcortex.user.domain.UserStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

class AccessTokenServiceTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"), ZoneOffset.UTC);
    private static final AccessTokenProperties PROPS = new AccessTokenProperties(Duration.ofMinutes(15));

    private final JwtEncoder encoder = mock(JwtEncoder.class);
    private final AccessTokenService service = new AccessTokenService(encoder, PROPS, FIXED);

    @Test
    void accessTokenTtlIsExposedFromProperties() {
        assertThat(service.accessTokenTtl()).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void mintDelegatesToEncoderWithExpectedClaims() {
        AppUser user = activeUser();
        Jwt stub = mock(Jwt.class);
        when(stub.getTokenValue()).thenReturn("signed-jwt");
        when(encoder.encode(any())).thenReturn(stub);

        String token = service.mint(user);

        assertThat(token).isEqualTo("signed-jwt");
        ArgumentCaptor<JwtEncoderParameters> captor = ArgumentCaptor.forClass(JwtEncoderParameters.class);
        verify(encoder).encode(captor.capture());
        org.springframework.security.oauth2.jwt.JwtClaimsSet claims = captor.getValue().getClaims();
        // Spring stores the issuer claim as a String, so we read it back via the raw map.
        assertThat(claims.getClaims()).containsEntry("iss", "flow-cortex");
        assertThat(claims.getSubject()).isEqualTo(user.getId().toString());
        assertThat(claims.getClaim("role").toString()).isEqualTo(Role.MEMBER.name());
        assertThat(claims.getClaims()).doesNotContainKey("tv");
    }

    private static AppUser activeUser() {
        UUID id = UUID.randomUUID();
        return new AppUser(id, id, "person@example.com", "hash", "Person", Role.MEMBER, UserStatus.ACTIVE);
    }
}
