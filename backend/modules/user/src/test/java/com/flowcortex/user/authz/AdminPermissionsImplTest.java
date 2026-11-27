package com.flowcortex.user.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.shared.web.MfaRequiredException;
import com.flowcortex.user.totp.TotpRequirement;
import com.flowcortex.user.totp.TotpService;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class AdminPermissionsImplTest {

    private static final Permission KEY = Permission.SUPPORT_TICKET_CLOSE;

    private final CallerFactsLoader loader = mock(CallerFactsLoader.class);
    private final TotpRequirement requirement = mock(TotpRequirement.class);
    private final TotpService totp = mock(TotpService.class);
    private final AdminPermissionsImpl permissions = new AdminPermissionsImpl(loader,
            mock(NamedParameterJdbcTemplate.class), requirement, totp);
    private final UUID staffId = UUID.randomUUID();

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private static CallerFacts staff(boolean sessionMfa) {
        return new CallerFacts(false, false, true, sessionMfa, Set.of(KEY));
    }

    private void signInWithMfaClaim(Boolean claim) {
        Jwt.Builder jwt = Jwt.withTokenValue("t").header("alg", "HS256").subject(staffId.toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (claim != null) {
            jwt.claim("mfa", claim);
        }
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt.build(), List.of()));
    }

    @Test
    void staffWithMfaClaimAndKeyPasses() {
        signInWithMfaClaim(true);
        when(requirement.adminRequired()).thenReturn(true);
        when(loader.load(staffId, true)).thenReturn(staff(true));

        assertThatCode(() -> permissions.require(staffId, KEY)).doesNotThrowAnyException();
    }

    @Test
    void staffWithoutMfaClaimIsMfaRequired() {
        signInWithMfaClaim(null);
        when(requirement.adminRequired()).thenReturn(true);
        when(loader.load(staffId, false)).thenReturn(staff(false));
        when(totp.isEnabled(staffId)).thenReturn(true);

        assertThatThrownBy(() -> permissions.require(staffId, KEY))
                .isInstanceOfSatisfying(MfaRequiredException.class, e -> assertThat(e.isEnrolled()).isTrue());
    }

    @Test
    void aCallerWithoutAJwtAuthenticationReadsAsWithoutMfa() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("someone", "n/a", List.of()));
        when(requirement.adminRequired()).thenReturn(true);
        when(loader.load(staffId, false)).thenReturn(staff(false));

        assertThatThrownBy(() -> permissions.require(staffId, KEY)).isInstanceOf(MfaRequiredException.class);
    }

    @Test
    void noAuthenticationReadsAsWithoutMfa() {
        when(requirement.adminRequired()).thenReturn(true);
        when(loader.load(staffId, false)).thenReturn(staff(false));

        assertThatThrownBy(() -> permissions.require(staffId, KEY)).isInstanceOf(MfaRequiredException.class);
    }

    @Test
    void requirementOffSkipsTheStep() {
        signInWithMfaClaim(null);
        when(requirement.adminRequired()).thenReturn(false);
        when(loader.load(staffId, false)).thenReturn(staff(false));

        assertThatCode(() -> permissions.require(staffId, KEY)).doesNotThrowAnyException();
    }
}
