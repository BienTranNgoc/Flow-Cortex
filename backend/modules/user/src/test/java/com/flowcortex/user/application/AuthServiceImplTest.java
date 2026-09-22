package com.flowcortex.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.user.api.CurrentUser;
import com.flowcortex.user.domain.AppUser;
import com.flowcortex.user.domain.Role;
import com.flowcortex.user.domain.UserStatus;
import com.flowcortex.user.infrastructure.AppUserRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

/**
 * Task 4.3 (design.md D4/R-021/R-023), authored before {@link
 * AuthServiceImpl} existed. {@link JwtEncoder} is mocked — the concrete
 * encoding is proven by {@code JwtSigningConfig}'s wiring plus the real HTTP
 * round-trip in {@code UserModuleEndpointsIT} (:boot:app); this test locks
 * WHAT gets minted (subject, role claim) and, just as importantly, when it
 * must NOT be minted at all.
 */
class AuthServiceImplTest {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();
    private static final String RAW_PASSWORD = "correct horse battery staple";

    private final AppUserRepository repository = mock(AppUserRepository.class);
    private final JwtEncoder jwtEncoder = mock(JwtEncoder.class);
    private final AuthServiceImpl service = new AuthServiceImpl(repository, ENCODER, jwtEncoder);

    private static AppUser activeUser(UUID id, Role role) {
        return new AppUser(id, id, "person@example.com", ENCODER.encode(RAW_PASSWORD), "Person", role,
                UserStatus.ACTIVE);
    }

    @Test
    void loginUnknownEmailThrowsBadCredentialsNotFound() {
        when(repository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login("nobody@example.com", RAW_PASSWORD))
                .isInstanceOf(BadCredentialsException.class);
        verify(jwtEncoder, never()).encode(any());
    }

    @Test
    void loginWrongPasswordThrowsBadCredentials() {
        UUID id = UUID.randomUUID();
        when(repository.findByEmail("person@example.com")).thenReturn(Optional.of(activeUser(id, Role.MEMBER)));

        assertThatThrownBy(() -> service.login("person@example.com", "totally-wrong-password"))
                .isInstanceOf(BadCredentialsException.class);
        verify(jwtEncoder, never()).encode(any());
    }

    @Test
    void loginSuspendedUserCorrectPasswordThrows403NotAToken() {
        UUID id = UUID.randomUUID();
        AppUser suspended = new AppUser(id, id, "person@example.com", ENCODER.encode(RAW_PASSWORD), "Person",
                Role.MEMBER, UserStatus.SUSPENDED);
        when(repository.findByEmail("person@example.com")).thenReturn(Optional.of(suspended));

        assertThatThrownBy(() -> service.login("person@example.com", RAW_PASSWORD))
                .isInstanceOf(AccessDeniedException.class);
        verify(jwtEncoder, never()).encode(any());
    }

    @Test
    void loginSuccessMintsAJwtWithSubjectEqualToUserIdAndRoleClaim() {
        UUID id = UUID.randomUUID();
        AppUser admin = activeUser(id, Role.ADMIN);
        when(repository.findByEmail("person@example.com")).thenReturn(Optional.of(admin));
        when(jwtEncoder.encode(any())).thenReturn(
                Jwt.withTokenValue("signed-token").header("alg", "HS256").claim("sub", id.toString()).build());

        String token = service.login("person@example.com", RAW_PASSWORD);

        assertThat(token).isEqualTo("signed-token");
        ArgumentCaptor<JwtEncoderParameters> captor = ArgumentCaptor.forClass(JwtEncoderParameters.class);
        verify(jwtEncoder).encode(captor.capture());
        assertThat(captor.getValue().getClaims().getSubject()).isEqualTo(id.toString());
        assertThat(captor.getValue().getClaims().getClaimAsString(JwtClaims.ROLE)).isEqualTo("ADMIN");
    }

    @Test
    void updateOwnProfileChangesNameOnly() {
        UUID id = UUID.randomUUID();
        AppUser user = activeUser(id, Role.MEMBER);
        when(repository.findById(id)).thenReturn(Optional.of(user));

        CurrentUser updated = service.updateOwnProfile(id, "New Display Name");

        assertThat(updated.name()).isEqualTo("New Display Name");
        assertThat(updated.email()).isEqualTo("person@example.com");
        assertThat(updated.role()).isEqualTo("MEMBER");
    }

    @Test
    void updateOwnProfileUnknownUserThrowsNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateOwnProfile(id, "New Name"))
                .isInstanceOf(NotFoundException.class);
    }
}
