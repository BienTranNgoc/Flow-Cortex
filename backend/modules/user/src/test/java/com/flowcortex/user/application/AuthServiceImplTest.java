package com.flowcortex.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.user.api.CurrentUser;
import com.flowcortex.user.api.TokenPairResponse;
import com.flowcortex.user.domain.AppUser;
import com.flowcortex.user.domain.Role;
import com.flowcortex.user.domain.UserStatus;
import com.flowcortex.user.infrastructure.AppUserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

class AuthServiceImplTest {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();
    private static final String RAW_PASSWORD = "correct horse battery staple";

    private static final String OWNER_EMAIL = "owner@example.com";
    private static final RegistrationProperties.Limit HOURLY = new RegistrationProperties.Limit(5, Duration.ofHours(1));

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"), ZoneOffset.UTC);

    private final AppUserRepository repository = mock(AppUserRepository.class);
    private final AccessTokenService accessTokenService = mock(AccessTokenService.class);
    private final RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);
    private final GoogleOAuthClient googleOAuthClient = mock(GoogleOAuthClient.class);
    private final OAuthStateStore oauthStateStore = mock(OAuthStateStore.class);
    private final GoogleOAuthProperties oauthProperties = new GoogleOAuthProperties("", "", java.util.List.of(),
            Duration.ofMinutes(10), true);
    private final AuditEventWriter audit = mock(AuditEventWriter.class);

    private final AuthServiceImpl service = serviceWithOwner(OWNER_EMAIL);

    private AuthServiceImpl serviceWithOwner(String ownerEmail) {
        return new AuthServiceImpl(repository, ENCODER, accessTokenService, refreshTokenService, googleOAuthClient,
                oauthStateStore, oauthProperties, audit, new RegistrationProperties(ownerEmail, HOURLY, HOURLY),
                FIXED_CLOCK);
    }

    private static AppUser activeUser(UUID id, Role role) {
        return new AppUser(id, id, "person@example.com", ENCODER.encode(RAW_PASSWORD), "Person", role,
                UserStatus.ACTIVE);
    }

    private AppUser givenOwner(Role role, UserStatus status) {
        UUID id = UUID.randomUUID();
        AppUser owner = new AppUser(id, id, OWNER_EMAIL, "hash", "Owner", role, status);
        when(repository.findByEmail(OWNER_EMAIL)).thenReturn(Optional.of(owner));
        return owner;
    }

    private void assertRegistrationClosed(AuthServiceImpl closed) {
        assertThatThrownBy(() -> closed.register("new@example.com", RAW_PASSWORD, "New Person", false, "127.0.0.1",
                "ua"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(e.getReason()).isEqualTo("Self-registration is not available");
                });
        verify(repository, never()).saveAndFlush(any());
        verify(accessTokenService, never()).mint(any());
    }

    @Test
    void loginUnknownEmailThrowsBadCredentialsNotFound() {
        when(repository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login("nobody@example.com", RAW_PASSWORD, false, "127.0.0.1", "ua"))
                .isInstanceOf(BadCredentialsException.class);
        verify(accessTokenService, never()).mint(any());
    }

    @Test
    void registerCreatesAMemberOwnedByTheConfiguredOwnerAndReturnsTokenPair() {
        AppUser owner = givenOwner(Role.ADMIN, UserStatus.ACTIVE);
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        TokenPairResponse pair = new TokenPairResponse("access-token", "refresh-token",
                Instant.parse("2026-10-29T00:15:00Z"), Instant.parse("2026-11-05T00:00:00Z"));
        when(refreshTokenService.issueFor(any(), any(), any(), any(Boolean.class))).thenReturn(
                new TokenPair(pair.accessToken(), pair.refreshToken(), pair.accessTokenExpiresAt(),
                        pair.refreshTokenExpiresAt()));

        TokenPairResponse response = service.register("new@example.com", RAW_PASSWORD, "New Person", false,
                "127.0.0.1", "ua");

        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.refreshToken()).isEqualTo("refresh-token");
        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
        verify(repository).saveAndFlush(captor.capture());
        AppUser saved = captor.getValue();
        assertThat(saved.getOwnerId()).isEqualTo(owner.getId());
        assertThat(saved.getId()).isNotEqualTo(owner.getId());
        assertThat(saved.getRole()).isEqualTo(Role.MEMBER);
        assertThat(saved.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(saved.getPasswordHash()).isNotEqualTo(RAW_PASSWORD);
        assertThat(saved.hasPassword()).isTrue();
    }

    @Test
    void registerWithNoOwnerConfiguredIsClosed() {
        assertRegistrationClosed(serviceWithOwner(null));
        assertRegistrationClosed(serviceWithOwner("  "));
        verify(repository, never()).findByEmail(any());
    }

    @Test
    void registerWithAnUnknownOwnerIsClosed() {
        when(repository.findByEmail(OWNER_EMAIL)).thenReturn(Optional.empty());
        assertRegistrationClosed(service);
    }

    @Test
    void registerWithANonAdminOwnerIsClosed() {
        givenOwner(Role.MEMBER, UserStatus.ACTIVE);
        assertRegistrationClosed(service);
    }

    @Test
    void registerWithASuspendedOwnerIsClosed() {
        givenOwner(Role.ADMIN, UserStatus.SUSPENDED);
        assertRegistrationClosed(service);
    }

    @Test
    void registerDuplicateEmailIsTranslatedToConflict() {
        givenOwner(Role.ADMIN, UserStatus.ACTIVE);
        when(repository.saveAndFlush(any()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("uq_app_user_email"));

        assertThatThrownBy(() -> service.register("taken@example.com", RAW_PASSWORD, "Taken", false, "127.0.0.1", "ua"))
                .isInstanceOf(ConflictException.class);
        verify(accessTokenService, never()).mint(any());
    }

    @Test
    void loginWrongPasswordThrowsBadCredentials() {
        UUID id = UUID.randomUUID();
        when(repository.findByEmail("person@example.com")).thenReturn(Optional.of(activeUser(id, Role.MEMBER)));

        assertThatThrownBy(() -> service.login("person@example.com", "totally-wrong-password", false, "127.0.0.1", "ua"))
                .isInstanceOf(BadCredentialsException.class);
        verify(accessTokenService, never()).mint(any());
    }

    @Test
    void loginAfterFiveWrongPasswordsLocksTheAccount() {
        UUID id = UUID.randomUUID();
        AppUser user = activeUser(id, Role.MEMBER);
        when(repository.findByEmail("person@example.com")).thenReturn(Optional.of(user));

        for (int i = 0; i < 5; i++) {
            final int attempt = i;
            assertThatThrownBy(() -> service.login("person@example.com", "wrong-" + attempt, false, "127.0.0.1", "ua"))
                    .isInstanceOf(BadCredentialsException.class);
        }

        assertThat(user.getFailedLoginAttempts()).isEqualTo((short) 5);
        assertThat(user.getLockedUntil()).isAfter(Instant.now(FIXED_CLOCK));
    }

    @Test
    void loginWhileLockedThrowsAccountLockedEvenWithCorrectPassword() {
        UUID id = UUID.randomUUID();
        AppUser user = activeUser(id, Role.MEMBER);
        Instant now = Instant.now(FIXED_CLOCK);
        for (int i = 0; i < 5; i++) {
            user.recordFailedLogin(5, Duration.ofMinutes(15), now);
        }
        when(repository.findByEmail("person@example.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.login("person@example.com", RAW_PASSWORD, false, "127.0.0.1", "ua"))
                .isInstanceOf(com.flowcortex.shared.web.AccountLockedException.class);
        verify(accessTokenService, never()).mint(any());
    }

    @Test
    void loginSuccessResetsFailedLoginCounter() {
        UUID id = UUID.randomUUID();
        AppUser user = activeUser(id, Role.MEMBER);
        user.recordFailedLogin(5, Duration.ofMinutes(15), Instant.now(FIXED_CLOCK));
        when(repository.findByEmail("person@example.com")).thenReturn(Optional.of(user));
        when(refreshTokenService.issueFor(any(), any(), any(), any(Boolean.class))).thenReturn(
                new TokenPair("a", "r", Instant.now(FIXED_CLOCK), Instant.now(FIXED_CLOCK)));

        service.login("person@example.com", RAW_PASSWORD, false, "127.0.0.1", "ua");

        assertThat(user.getFailedLoginAttempts()).isZero();
        assertThat(user.getLockedUntil()).isNull();
    }

    @Test
    void loginSuspendedUserCorrectPasswordThrows403NotAToken() {
        UUID id = UUID.randomUUID();
        AppUser suspended = new AppUser(id, id, "person@example.com", ENCODER.encode(RAW_PASSWORD), "Person",
                Role.MEMBER, UserStatus.SUSPENDED);
        when(repository.findByEmail("person@example.com")).thenReturn(Optional.of(suspended));

        assertThatThrownBy(() -> service.login("person@example.com", RAW_PASSWORD, false, "127.0.0.1", "ua"))
                .isInstanceOf(AccessDeniedException.class);
        verify(accessTokenService, never()).mint(any());
    }

    @Test
    void loginSuccessIssuesTokenPair() {
        UUID id = UUID.randomUUID();
        AppUser admin = activeUser(id, Role.ADMIN);
        when(repository.findByEmail("person@example.com")).thenReturn(Optional.of(admin));
        when(refreshTokenService.issueFor(any(), any(), any(), any(Boolean.class))).thenReturn(
                new TokenPair("signed-access", "signed-refresh", Instant.parse("2026-10-29T00:15:00Z"),
                        Instant.parse("2026-11-05T00:00:00Z")));

        TokenPairResponse response = service.login("person@example.com", RAW_PASSWORD, false, "127.0.0.1", "ua");

        assertThat(response.accessToken()).isEqualTo("signed-access");
        assertThat(response.refreshToken()).isEqualTo("signed-refresh");
        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
        verify(refreshTokenService).issueFor(captor.capture(), any(), any(), any(Boolean.class));
        assertThat(captor.getValue().getId()).isEqualTo(id);
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
