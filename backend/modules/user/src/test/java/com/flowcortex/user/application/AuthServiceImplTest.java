package com.flowcortex.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flowcortex.shared.web.AccountLockedException;
import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.shared.web.UnprocessableEntityException;
import com.flowcortex.user.api.AuthSession;
import com.flowcortex.user.api.ConfirmedEmailSession;
import com.flowcortex.user.api.CurrentUser;
import com.flowcortex.user.api.OAuthFailure;
import com.flowcortex.user.api.OAuthLoginException;
import com.flowcortex.user.api.PasswordSetup;
import com.flowcortex.user.api.UserActivatedEvent;
import com.flowcortex.user.domain.AppUser;
import com.flowcortex.user.domain.Role;
import com.flowcortex.user.domain.UserStatus;
import com.flowcortex.user.infrastructure.AppUserRepository;
import java.time.Clock;
import com.flowcortex.user.emailconfirm.EmailConfirmationProperties;
import com.flowcortex.user.emailconfirm.EmailConfirmationService;
import com.flowcortex.user.emailconfirm.RegistrationConflictException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;

class AuthServiceImplTest {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();
    private static final String RAW_PASSWORD = "correct horse battery staple";

    private static final String OWNER_EMAIL = "owner@example.com";
    private static final RegistrationProperties.Limit HOURLY = new RegistrationProperties.Limit(5, Duration.ofHours(1));

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"), ZoneOffset.UTC);
    private static final Instant NOW = Instant.now(FIXED_CLOCK);
    private static final AuthSession SESSION = new AuthSession("signed-access", 900L, "signed-refresh",
            Instant.parse("2026-11-05T00:00:00Z"), false);

    private final AppUserRepository repository = mock(AppUserRepository.class);
    private final RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);
    private final LoginAttemptRecorder loginAttempts = mock(LoginAttemptRecorder.class);
    private final LoginRateLimiter loginRateLimiter = mock(LoginRateLimiter.class);
    private final RegistrationRateLimiter registrationRateLimiter = mock(RegistrationRateLimiter.class);
    private final GoogleOAuthClient googleOAuthClient = mock(GoogleOAuthClient.class);
    private final AuditEventWriter audit = mock(AuditEventWriter.class);

    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final EmailConfirmationService emailConfirmation = mock(EmailConfirmationService.class);

    private final AuthServiceImpl service = serviceWithOwner(OWNER_EMAIL);

    private AuthServiceImpl serviceWithOwner(String ownerEmail) {
        return service(ownerEmail, false);
    }

    private AuthServiceImpl service(String ownerEmail, boolean confirmationEnabled) {
        return new AuthServiceImpl(repository, ENCODER, refreshTokenService, loginAttempts, loginRateLimiter,
                registrationRateLimiter, googleOAuthClient, audit,
                new RegistrationProperties(ownerEmail, HOURLY, HOURLY), FIXED_CLOCK,
                events, mock(PlatformTransactionManager.class), emailConfirmation,
                new EmailConfirmationProperties(confirmationEnabled, Duration.ofHours(24), "no-reply@flow-cortex.dev",
                        Duration.ofHours(72), Duration.ofMinutes(30)));
    }

    private AppUser unconfirmedMember(String email) {
        UUID id = UUID.randomUUID();
        AppUser user = AppUser.selfRegistered(id, UUID.randomUUID(), email, ENCODER.encode("attacker-password"),
                "Old", "Name", "old_name", null);
        when(repository.findIdByEmail(email)).thenReturn(Optional.of(id));
        when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(user));
        return user;
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

    private AppUser givenMember(UserStatus status) {
        UUID id = UUID.randomUUID();
        AppUser user = new AppUser(id, id, "person@example.com", ENCODER.encode(RAW_PASSWORD), "Person", Role.MEMBER,
                status);
        when(repository.findByEmail("person@example.com")).thenReturn(Optional.of(user));
        return user;
    }

    private void assertRegistrationClosed(AuthServiceImpl closed) {
        assertThatThrownBy(() -> closed.register("new@example.com", RAW_PASSWORD, "New", "Person", null, false,
                "127.0.0.1", "ua"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(e.getReason()).isEqualTo("Self-registration is not available");
                });
        verify(repository, never()).saveAndFlush(any());
        verify(refreshTokenService, never()).issueFor(any(), any(), any(), any(Boolean.class));
    }

    @Test
    void loginUnknownEmailIsBadCredentialsAndIsAuditedAndCountedAgainstTheIp() {
        when(repository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login("nobody@example.com", RAW_PASSWORD, false, "127.0.0.1", "ua"))
                .isInstanceOf(BadCredentialsException.class);

        verify(loginRateLimiter).recordFailure("127.0.0.1");
        verify(audit).write(eq(AuthEventType.LOGIN_FAILED_BAD_CREDENTIALS), isNull(), eq("127.0.0.1"), eq("ua"),
                eq(Map.of("email", "nobody@example.com")));
        verifyNoInteractions(loginAttempts);
        verify(refreshTokenService, never()).issueFor(any(), any(), any(), any(Boolean.class));
    }

    @Test
    void loginWrongPasswordRecordsTheFailureInItsOwnTransactionAndAuditsIt() {
        AppUser user = givenMember(UserStatus.ACTIVE);

        assertThatThrownBy(() -> service.login("person@example.com", "totally-wrong-password", false, "127.0.0.1",
                "ua")).isInstanceOf(BadCredentialsException.class);

        verify(loginAttempts).recordFailure(user.getId());
        verify(loginRateLimiter).recordFailure("127.0.0.1");
        verify(audit).write(eq(AuthEventType.LOGIN_FAILED_BAD_CREDENTIALS), eq(user.getId()), eq("127.0.0.1"),
                eq("ua"), any());
        verify(refreshTokenService, never()).issueFor(any(), any(), any(), any(Boolean.class));
    }

    @Test
    void loginAThrottledIpIsRefusedBeforeAnythingIsLookedUp() {
        doThrow(new LoginRateLimitedException(Duration.ofMinutes(5))).when(loginRateLimiter)
                .requireWithinLimit("127.0.0.1");

        assertThatThrownBy(() -> service.login("person@example.com", RAW_PASSWORD, false, "127.0.0.1", "ua"))
                .isInstanceOf(LoginRateLimitedException.class);

        verifyNoInteractions(repository);
    }

    @Test
    void loginLockedAccountWithTheRightPasswordIsRefusedAndAudited() {
        AppUser user = givenMember(UserStatus.ACTIVE);
        user.recordFailedLogin(1, Duration.ofMinutes(15), NOW);

        assertThatThrownBy(() -> service.login("person@example.com", RAW_PASSWORD, false, "127.0.0.1", "ua"))
                .isInstanceOf(AccountLockedException.class);

        verify(audit).write(eq(AuthEventType.LOGIN_FAILED_ACCOUNT_LOCKED), eq(user.getId()), any(), any(), any());
        verify(refreshTokenService, never()).issueFor(any(), any(), any(), any(Boolean.class));
    }

    @Test
    void loginLockedAccountIsRefusedBeforeThePasswordIsComparedWhateverThePassword() {
        AppUser user = givenMember(UserStatus.ACTIVE);
        user.recordFailedLogin(1, Duration.ofMinutes(15), NOW);

        for (String password : new String[] {RAW_PASSWORD, "wrong-password-x"}) {
            AccountLockedException refused = catchThrowableOfType(AccountLockedException.class,
                    () -> service.login("person@example.com", password, false, "127.0.0.1", "ua"));
            assertThat(refused).isNotNull();
            assertThat(refused.getLockedUntil()).isEqualTo(user.getLockedUntil());
        }

        verify(loginAttempts, never()).recordFailure(any());
        verify(loginRateLimiter, never()).recordFailure(any());
        verify(audit, times(2)).write(eq(AuthEventType.LOGIN_FAILED_ACCOUNT_LOCKED), eq(user.getId()), any(), any(),
                any());
    }

    @Test
    void loginForAnAccountWithoutAPasswordIsTheGeneric401AndNeverCountsTowardTheLockout() {
        UUID id = UUID.randomUUID();
        AppUser googleOnly = new AppUser(id, id, "person@example.com", "", "Person", Role.MEMBER, UserStatus.ACTIVE,
                false);
        when(repository.findByEmail("person@example.com")).thenReturn(Optional.of(googleOnly));

        assertThatThrownBy(() -> service.login("person@example.com", "", false, "127.0.0.1", "ua"))
                .isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> service.login("person@example.com", RAW_PASSWORD, false, "127.0.0.1", "ua"))
                .isInstanceOf(BadCredentialsException.class);

        verifyNoInteractions(loginAttempts);
        verify(audit, times(2)).write(eq(AuthEventType.LOGIN_FAILED_BAD_CREDENTIALS), eq(id), any(), any(), any());
    }

    @Test
    void loginTrimsAndLowerCasesTheEmailBeforeTheLookup() {
        UUID id = UUID.randomUUID();
        AppUser user = activeUser(id, Role.MEMBER);
        when(repository.findByEmail("person@example.com")).thenReturn(Optional.of(user));
        when(refreshTokenService.issueFor(any(), any(), any(), any(Boolean.class))).thenReturn(SESSION);

        assertThat(service.login("  Person@Example.COM ", RAW_PASSWORD, false, "127.0.0.1", "ua")).isEqualTo(SESSION);
    }

    @Test
    void loginSuspendedAccountWithTheRightPasswordIsForbiddenAndAudited() {
        AppUser user = givenMember(UserStatus.SUSPENDED);

        assertThatThrownBy(() -> service.login("person@example.com", RAW_PASSWORD, false, "127.0.0.1", "ua"))
                .isInstanceOf(AccessDeniedException.class);

        verify(audit).write(eq(AuthEventType.LOGIN_FAILED_ACCOUNT_SUSPENDED), eq(user.getId()), any(), any(), any());
        verify(refreshTokenService, never()).issueFor(any(), any(), any(), any(Boolean.class));
    }

    @Test
    void loginSuspendedAccountWithAWrongPasswordDoesNotRevealTheSuspension() {
        givenMember(UserStatus.SUSPENDED);

        assertThatThrownBy(() -> service.login("person@example.com", "wrong-password-x", false, "127.0.0.1", "ua"))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void loginSuccessIssuesTheSessionAndAuditsIt() {
        UUID id = UUID.randomUUID();
        AppUser user = activeUser(id, Role.ADMIN);
        when(repository.findByEmail("person@example.com")).thenReturn(Optional.of(user));
        when(refreshTokenService.issueFor(user, "127.0.0.1", "ua", true)).thenReturn(SESSION);

        AuthSession session = service.login("person@example.com", RAW_PASSWORD, true, "127.0.0.1", "ua");

        assertThat(session).isEqualTo(SESSION);
        verify(audit).write(eq(AuthEventType.LOGIN_SUCCESS), eq(id), eq("127.0.0.1"), eq("ua"), any());
        verify(loginAttempts, never()).recordSuccess(any());
    }

    @Test
    void loginSuccessAfterFailuresResetsTheCounter() {
        UUID id = UUID.randomUUID();
        AppUser user = activeUser(id, Role.MEMBER);
        user.recordFailedLogin(5, Duration.ofMinutes(15), NOW);
        when(repository.findByEmail("person@example.com")).thenReturn(Optional.of(user));
        when(refreshTokenService.issueFor(any(), any(), any(), any(Boolean.class))).thenReturn(SESSION);

        service.login("person@example.com", RAW_PASSWORD, false, "127.0.0.1", "ua");

        verify(loginAttempts).recordSuccess(id);
    }

    @Test
    void registerCreatesAMemberOwnedByTheConfiguredOwnerAndReturnsTheSession() {
        AppUser owner = givenOwner(Role.ADMIN, UserStatus.ACTIVE);
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(refreshTokenService.issueFor(any(), any(), any(), any(Boolean.class))).thenReturn(SESSION);

        AuthSession session = service.register("new@example.com", RAW_PASSWORD, "New", "Person", null, false,
                "127.0.0.1", "ua").orElseThrow();

        assertThat(session).isEqualTo(SESSION);
        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
        verify(repository).saveAndFlush(captor.capture());
        AppUser saved = captor.getValue();
        assertThat(saved.getOwnerId()).isEqualTo(owner.getId());
        assertThat(saved.getId()).isNotEqualTo(owner.getId());
        assertThat(saved.getRole()).isEqualTo(Role.MEMBER);
        assertThat(saved.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(saved.getPasswordHash()).isNotEqualTo(RAW_PASSWORD);
        assertThat(saved.hasPassword()).isTrue();
        verify(registrationRateLimiter).acquire("127.0.0.1", "new@example.com");
        verify(audit).write(eq(AuthEventType.REGISTRATION_ACCEPTED), eq(saved.getId()), eq("127.0.0.1"), eq("ua"),
                any());
        verify(audit, never()).write(eq(AuthEventType.LOGIN_SUCCESS), any(), any(), any(), any());
    }

    @Test
    void registerTrimsAndLowerCasesTheStoredEmail() {
        givenOwner(Role.ADMIN, UserStatus.ACTIVE);
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(refreshTokenService.issueFor(any(), any(), any(), any(Boolean.class))).thenReturn(SESSION);

        service.register(" New@Example.COM ", RAW_PASSWORD, "New", "Person", null, false, "127.0.0.1", "ua");

        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
        verify(repository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getEmail()).isEqualTo("new@example.com");
        verify(registrationRateLimiter).acquire("127.0.0.1", "new@example.com");
    }

    @Test
    void registerOverTheRateLimitIsAuditedAndRethrown() {
        doThrow(new RegistrationRateLimitedException(Duration.ofMinutes(1))).when(registrationRateLimiter)
                .acquire("127.0.0.1", "new@example.com");

        assertThatThrownBy(() -> service.register("new@example.com", RAW_PASSWORD, "New", "Person", null, false,
                "127.0.0.1", "ua"))
                .isInstanceOf(RegistrationRateLimitedException.class);

        verify(audit).write(eq(AuthEventType.REGISTRATION_REJECTED_RATE_LIMITED), isNull(), eq("127.0.0.1"),
                eq("ua"), any());
        verifyNoInteractions(repository);
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

        assertThatThrownBy(() -> service.register("taken@example.com", RAW_PASSWORD, "Taken", "Member", null, false,
                "127.0.0.1", "ua"))
                .isInstanceOf(ConflictException.class);
        verify(refreshTokenService, never()).issueFor(any(), any(), any(), any(Boolean.class));
    }

    @Test
    void registerWithConfirmationOffPublishesTheActivationAndMailsNoLink() {
        givenOwner(Role.ADMIN, UserStatus.ACTIVE);
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(refreshTokenService.issueFor(any(), any(), any(), any(Boolean.class))).thenReturn(SESSION);

        service.register("new@example.com", RAW_PASSWORD, "New", "Person", null, false, "127.0.0.1", "ua");

        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
        verify(repository).saveAndFlush(captor.capture());
        verify(events).publishEvent(new UserActivatedEvent(captor.getValue().getId()));
        verifyNoInteractions(emailConfirmation);
    }

    @Test
    void registerWithConfirmationOnMailsALinkAndPublishesNoActivation() {
        givenOwner(Role.ADMIN, UserStatus.ACTIVE);
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(service(OWNER_EMAIL, true).register("new@example.com", RAW_PASSWORD, "New", "Person", null, false,
                "127.0.0.1", "ua")).isEmpty();

        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
        verify(repository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().isEmailConfirmed()).isFalse();
        verify(emailConfirmation).issue(captor.getValue().getId(), "new@example.com");
        verifyNoInteractions(events);
    }

    @Test
    void registerOverAnUnconfirmedAccountReplacesWhatItChoseAndMailsAFreshLink() {
        givenOwner(Role.ADMIN, UserStatus.ACTIVE);
        AppUser unconfirmed = unconfirmedMember("person@example.com");
        String oldHash = unconfirmed.getPasswordHash();

        assertThat(service(OWNER_EMAIL, true).register("Person@Example.com", RAW_PASSWORD, " Binh ", "Le", "binh_le",
                false, "127.0.0.1", "ua")).isEmpty();

        assertThat(unconfirmed.getPasswordHash()).isNotEqualTo(oldHash);
        assertThat(ENCODER.matches(RAW_PASSWORD, unconfirmed.getPasswordHash())).isTrue();
        assertThat(unconfirmed.getFirstName()).isEqualTo("Binh");
        assertThat(unconfirmed.getName()).isEqualTo("Binh Le");
        assertThat(unconfirmed.getUsername()).isEqualTo("binh_le");
        assertThat(unconfirmed.isEmailConfirmed()).isFalse();
        verify(repository).saveAndFlush(unconfirmed);
        verify(emailConfirmation).reissue(unconfirmed.getId(), "person@example.com");
        verify(emailConfirmation, never()).issue(any(), any());
        verify(audit).write(eq(AuthEventType.REGISTRATION_ACCEPTED), eq(unconfirmed.getId()), any(), any(), any());
    }

    @Test
    void registerOverAConfirmedAccountIsTheEmailConflictAndChangesNothing() {
        givenOwner(Role.ADMIN, UserStatus.ACTIVE);
        AppUser confirmed = activeUser(UUID.randomUUID(), Role.MEMBER);
        String hash = confirmed.getPasswordHash();
        when(repository.findIdByEmail("person@example.com")).thenReturn(Optional.of(confirmed.getId()));
        when(repository.findByIdForUpdate(confirmed.getId())).thenReturn(Optional.of(confirmed));

        assertThatThrownBy(() -> service(OWNER_EMAIL, true).register("person@example.com", "other-password-1", "A",
                "B", null, false, "127.0.0.1", "ua"))
                .isInstanceOf(ConflictException.class).hasMessage("An account with this email already exists");

        assertThat(confirmed.getPasswordHash()).isEqualTo(hash);
        verify(repository, never()).saveAndFlush(any());
        verifyNoInteractions(emailConfirmation, events);
    }

    @Test
    void theFirstConfirmationEndsEarlierSessionsPublishesTheActivationAndAsksForAPassword() {
        AppUser user = AppUser.selfRegistered(UUID.randomUUID(), UUID.randomUUID(), "person@example.com", "", "A",
                "B", null, null);
        user.confirmEmailDroppingUnprovenChoices(NOW, null);
        when(emailConfirmation.confirm("raw-token"))
                .thenReturn(Optional.of(new EmailConfirmationService.Confirmed(user, true)));
        when(emailConfirmation.issuePasswordSetupToken(user.getId())).thenReturn("setup-token");
        when(refreshTokenService.issueFor(user, "127.0.0.1", "ua", false)).thenReturn(SESSION);

        ConfirmedEmailSession confirmed = service.confirmEmail("raw-token", "127.0.0.1", "ua");

        assertThat(confirmed.session()).isEqualTo(SESSION);
        assertThat(confirmed.passwordSetupRequired()).isTrue();
        assertThat(confirmed.passwordSetupToken()).isEqualTo("setup-token");
        InOrder order = inOrder(refreshTokenService);
        order.verify(refreshTokenService).revokeAllFor(user.getId());
        order.verify(refreshTokenService).issueFor(user, "127.0.0.1", "ua", false);
        verify(events).publishEvent(new UserActivatedEvent(user.getId()));
    }

    @Test
    void anUnusableLinkIsTheLinkInvalidProblemAndSignsNobodyIn() {
        when(emailConfirmation.confirm("raw-token")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirmEmail("raw-token", "127.0.0.1", "ua"))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessage("This confirmation link is no longer valid");

        verifyNoInteractions(refreshTokenService, events);
    }

    @Test
    void aConfirmationOfAnAccountThatKeepsItsPasswordHandsOutNoSetupToken() {
        AppUser user = activeUser(UUID.randomUUID(), Role.MEMBER);
        when(emailConfirmation.confirm("raw-token"))
                .thenReturn(Optional.of(new EmailConfirmationService.Confirmed(user, false)));
        when(refreshTokenService.issueFor(user, "127.0.0.1", "ua", false)).thenReturn(SESSION);

        ConfirmedEmailSession confirmed = service.confirmEmail("raw-token", "127.0.0.1", "ua");

        assertThat(confirmed.passwordSetupRequired()).isFalse();
        assertThat(confirmed.passwordSetupToken()).isNull();
        verify(emailConfirmation, never()).issuePasswordSetupToken(any());
    }

    @Test
    void anOpenedSetPasswordLinkSignsInWithASetupToken() {
        AppUser user = new AppUser(UUID.randomUUID(), UUID.randomUUID(), "person@example.com", "", "P", Role.MEMBER,
                UserStatus.ACTIVE, false);
        when(emailConfirmation.openPasswordLink("raw-link")).thenReturn(Optional.of(user));
        when(emailConfirmation.issuePasswordSetupToken(user.getId())).thenReturn("setup-token");
        when(refreshTokenService.issueFor(user, "127.0.0.1", "ua", false)).thenReturn(SESSION);

        ConfirmedEmailSession opened = service.openPasswordLink("raw-link", "127.0.0.1", "ua");

        assertThat(opened.session()).isEqualTo(SESSION);
        assertThat(opened.passwordSetupRequired()).isTrue();
        assertThat(opened.passwordSetupToken()).isEqualTo("setup-token");
    }

    @Test
    void anUnusableSetPasswordLinkIsOneProblemAndSignsNobodyIn() {
        when(emailConfirmation.openPasswordLink("raw-link")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.openPasswordLink("raw-link", "127.0.0.1", "ua"))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessage("This set-password link is no longer valid");

        verifyNoInteractions(refreshTokenService);
    }

    @Test
    void setPasswordSpendsTheSetupTokenTakesTheChosenIdentityEndsEverySessionAndStartsANewOne() {
        AppUser user = new AppUser(UUID.randomUUID(), UUID.randomUUID(), "person@example.com", "", "P", Role.MEMBER,
                UserStatus.ACTIVE, false);
        when(repository.findByIdForUpdate(user.getId())).thenReturn(Optional.of(user));
        when(emailConfirmation.spendPasswordSetupToken(user.getId(), "setup-token")).thenReturn(true);
        when(refreshTokenService.issueFor(user, "127.0.0.1", "ua", false)).thenReturn(SESSION);

        AuthSession session = service.setPassword(user.getId(), setup("setup-token"), "127.0.0.1", "ua");

        assertThat(session).isEqualTo(SESSION);
        assertThat(user.hasPassword()).isTrue();
        assertThat(ENCODER.matches("new-password-1", user.getPasswordHash())).isTrue();
        assertThat(user.getName()).isEqualTo("Binh Le");
        assertThat(user.getUsername()).isEqualTo("binh_le");
        InOrder order = inOrder(refreshTokenService);
        order.verify(refreshTokenService).revokeAllFor(user.getId());
        order.verify(refreshTokenService).issueFor(user, "127.0.0.1", "ua", false);
        verify(emailConfirmation).retireOpenLinks(user.getId());
        verify(emailConfirmation).mailPasswordSetNotice(user.getId(), "person@example.com");
        verify(audit).write(eq(AuthEventType.PASSWORD_SET), eq(user.getId()), eq("127.0.0.1"), eq("ua"), any());
    }

    @Test
    void setPasswordWithoutAUsableSetupTokenIsRefusedAndChangesNothing() {
        AppUser user = new AppUser(UUID.randomUUID(), UUID.randomUUID(), "person@example.com", "", "P", Role.MEMBER,
                UserStatus.ACTIVE, false);
        when(repository.findByIdForUpdate(user.getId())).thenReturn(Optional.of(user));
        when(emailConfirmation.spendPasswordSetupToken(user.getId(), "stolen-or-stale")).thenReturn(false);

        assertThatThrownBy(() -> service.setPassword(user.getId(), setup("stolen-or-stale"), "127.0.0.1", "ua"))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessage("This password setup has expired; ask for a new set-password link");

        assertThat(user.hasPassword()).isFalse();
        assertThat(user.getName()).isEqualTo("P");
        verifyNoInteractions(refreshTokenService);
        verify(emailConfirmation, never()).mailPasswordSetNotice(any(), any());
    }

    @Test
    void setPasswordWithATakenUsernameIsTheUsernameConflict() {
        AppUser user = new AppUser(UUID.randomUUID(), UUID.randomUUID(), "person@example.com", "", "P", Role.MEMBER,
                UserStatus.ACTIVE, false);
        when(repository.findByIdForUpdate(user.getId())).thenReturn(Optional.of(user));
        when(emailConfirmation.spendPasswordSetupToken(user.getId(), "setup-token")).thenReturn(true);
        when(repository.saveAndFlush(user)).thenThrow(new DataIntegrityViolationException(
                "duplicate key value violates unique constraint \"uq_app_user_username\""));

        RegistrationConflictException conflict = catchThrowableOfType(RegistrationConflictException.class,
                () -> service.setPassword(user.getId(), setup("setup-token"), "127.0.0.1", "ua"));

        assertThat(conflict.getField()).isEqualTo("username");
        verify(refreshTokenService, never()).issueFor(any(), any(), any(), any(Boolean.class));
    }

    @Test
    void setPasswordOnAnAccountThatHasOneIsAConflictAndKeepsIt() {
        AppUser user = activeUser(UUID.randomUUID(), Role.MEMBER);
        String hash = user.getPasswordHash();
        when(repository.findByIdForUpdate(user.getId())).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.setPassword(user.getId(), setup("setup-token"), "127.0.0.1", "ua"))
                .isInstanceOf(ConflictException.class).hasMessage("This account already has a password");

        assertThat(user.getPasswordHash()).isEqualTo(hash);
        verify(audit, never()).write(eq(AuthEventType.PASSWORD_SET), any(), any(), any(), any());
    }

    @Test
    void setPasswordOnASuspendedAccountIsForbidden() {
        AppUser user = new AppUser(UUID.randomUUID(), UUID.randomUUID(), "person@example.com", "", "P", Role.MEMBER,
                UserStatus.SUSPENDED, false);
        when(repository.findByIdForUpdate(user.getId())).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.setPassword(user.getId(), setup("setup-token"), "127.0.0.1", "ua"))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(user.hasPassword()).isFalse();
    }

    @Test
    void confirmingASuspendedAccountPublishesNoActivationAndSignsNobodyIn() {
        AppUser user = AppUser.selfRegistered(UUID.randomUUID(), UUID.randomUUID(), "person@example.com", "", "A",
                "B", null, null);
        user.changeStatus(UserStatus.SUSPENDED);
        user.confirmEmailDroppingUnprovenChoices(NOW, null);
        when(emailConfirmation.confirm("raw-token"))
                .thenReturn(Optional.of(new EmailConfirmationService.Confirmed(user, true)));

        assertThatThrownBy(() -> service.confirmEmail("raw-token", "127.0.0.1", "ua"))
                .isInstanceOf(AccessDeniedException.class);

        verify(refreshTokenService).revokeAllFor(user.getId());
        verify(refreshTokenService, never()).issueFor(any(), any(), any(), any(Boolean.class));
        verifyNoInteractions(events);
    }

    @Test
    void registeringOverASuspendedUnconfirmedAccountWithConfirmationOffIsForbiddenLikeItsLogin() {
        givenOwner(Role.ADMIN, UserStatus.ACTIVE);
        AppUser unconfirmed = unconfirmedMember("person@example.com");
        unconfirmed.changeStatus(UserStatus.SUSPENDED);
        when(refreshTokenService.issueFor(any(), any(), any(), any(Boolean.class))).thenReturn(SESSION);

        assertThatThrownBy(() -> service.register("person@example.com", RAW_PASSWORD, "Binh", "Le", null, false,
                "127.0.0.1", "ua")).isInstanceOf(AccessDeniedException.class);

        verify(emailConfirmation).retireOpenLinks(unconfirmed.getId());
        verify(refreshTokenService, never()).issueFor(any(), any(), any(), any(Boolean.class));
        verify(audit).write(eq(AuthEventType.LOGIN_FAILED_ACCOUNT_SUSPENDED), eq(unconfirmed.getId()),
                eq("127.0.0.1"), eq("ua"), any());
        verifyNoInteractions(events);
    }

    private static PasswordSetup setup(String setupToken) {
        return new PasswordSetup(setupToken, "new-password-1", " Binh ", "Le ", "binh_le");
    }

    private void givenGoogleIdentity(String sub, String email) {
        when(googleOAuthClient.exchangeAndVerify("code", "verifier", "nonce"))
                .thenReturn(new GoogleIdTokenClaims(sub, email, true));
    }

    private OAuthFailure googleLoginFails() {
        return catchThrowableOfType(OAuthLoginException.class,
                () -> service.loginWithGoogle("code", "verifier", "nonce", false, "127.0.0.1", "ua")).failure();
    }

    private void verifyOAuthFailureAudited(OAuthFailure failure) {
        verify(audit).write(eq(AuthEventType.OAUTH_LOGIN_FAILED), any(), eq("127.0.0.1"), eq("ua"),
                argThat(metadata -> failure.code().equals(metadata.get("reason"))));
        verify(refreshTokenService, never()).issueFor(any(), any(), any(), any(Boolean.class));
    }

    @Test
    void googleLoginOfALinkedActiveUserIssuesASessionAndAuditsIt() {
        AppUser user = activeUser(UUID.randomUUID(), Role.MEMBER);
        givenGoogleIdentity("sub-1", "person@example.com");
        when(repository.findByGoogleSubject("sub-1")).thenReturn(Optional.of(user));
        when(refreshTokenService.issueFor(user, "127.0.0.1", "ua", true)).thenReturn(SESSION);

        AuthSession session = service.loginWithGoogle("code", "verifier", "nonce", true, "127.0.0.1", "ua");

        assertThat(session).isEqualTo(SESSION);
        verify(audit).write(eq(AuthEventType.OAUTH_LOGIN_SUCCESS), eq(user.getId()), eq("127.0.0.1"), eq("ua"), any());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void googleLoginOfASuspendedLinkedUserIsAccountInactive() {
        AppUser user = new AppUser(UUID.randomUUID(), UUID.randomUUID(), "person@example.com", "", "P", Role.MEMBER,
                UserStatus.SUSPENDED, false);
        givenGoogleIdentity("sub-1", "person@example.com");
        when(repository.findByGoogleSubject("sub-1")).thenReturn(Optional.of(user));

        assertThat(googleLoginFails()).isEqualTo(OAuthFailure.ACCOUNT_INACTIVE);
        verifyOAuthFailureAudited(OAuthFailure.ACCOUNT_INACTIVE);
    }

    @Test
    void googleLoginOfALockedLinkedUserIsAccountInactive() {
        AppUser user = activeUser(UUID.randomUUID(), Role.MEMBER);
        for (int i = 0; i < 5; i++) {
            user.recordFailedLogin(5, Duration.ofMinutes(15), NOW);
        }
        givenGoogleIdentity("sub-1", "person@example.com");
        when(repository.findByGoogleSubject("sub-1")).thenReturn(Optional.of(user));

        assertThat(googleLoginFails()).isEqualTo(OAuthFailure.ACCOUNT_INACTIVE);
        verifyOAuthFailureAudited(OAuthFailure.ACCOUNT_INACTIVE);
    }

    @Test
    void googleLoginWhoseEmailBelongsToAConfirmedAccountRequiresALinkAndNeverLinksOrCreates() {
        AppUser confirmed = activeUser(UUID.randomUUID(), Role.MEMBER);
        givenGoogleIdentity("sub-new", "  Person@Example.COM ");
        when(repository.findByGoogleSubject("sub-new")).thenReturn(Optional.empty());
        when(repository.findIdByEmail("person@example.com")).thenReturn(Optional.of(confirmed.getId()));
        when(repository.findByIdForUpdate(confirmed.getId())).thenReturn(Optional.of(confirmed));

        assertThat(googleLoginFails()).isEqualTo(OAuthFailure.LINK_REQUIRED);

        verifyOAuthFailureAudited(OAuthFailure.LINK_REQUIRED);
        assertThat(confirmed.getGoogleSubject()).isNull();
        assertThat(confirmed.hasPassword()).isTrue();
        verify(repository, never()).save(any());
        verify(repository, never()).saveAndFlush(any());
        verifyNoInteractions(registrationRateLimiter, events);
    }

    @Test
    void googleLoginWhoseEmailBelongsToAnUnconfirmedAccountTakesItOverAndDropsItsPassword() {
        AppUser unconfirmed = unconfirmedMember("person@example.com");
        UUID id = unconfirmed.getId();
        givenGoogleIdentity("sub-new", "person@example.com");
        when(repository.findByGoogleSubject("sub-new")).thenReturn(Optional.empty());
        when(refreshTokenService.issueFor(unconfirmed, "127.0.0.1", "ua", false)).thenReturn(SESSION);

        AuthSession session = service.loginWithGoogle("code", "verifier", "nonce", false, "127.0.0.1", "ua");

        assertThat(session).isEqualTo(SESSION);
        assertThat(unconfirmed.getGoogleSubject()).isEqualTo("sub-new");
        assertThat(unconfirmed.isEmailConfirmed()).isTrue();
        assertThat(unconfirmed.hasPassword()).isFalse();
        assertThat(unconfirmed.getPasswordHash()).isEmpty();
        verify(repository).saveAndFlush(unconfirmed);
        verify(emailConfirmation).retireOpenLinks(id);
        verify(refreshTokenService).revokeAllFor(id);
        verify(events).publishEvent(new UserActivatedEvent(id));
        verify(audit).write(eq(AuthEventType.OAUTH_UNCONFIRMED_ACCOUNT_CLAIMED), eq(id), any(), any(), any());
        verifyNoInteractions(registrationRateLimiter);
    }

    @Test
    void aGoogleTakeoverKeepsNoIdentityTheSquatterChoseAndTakesTheGoogleName() {
        AppUser unconfirmed = unconfirmedMember("person@example.com");
        when(googleOAuthClient.exchangeAndVerify("code", "verifier", "nonce"))
                .thenReturn(new GoogleIdTokenClaims("sub-new", "person@example.com", true, "Nguyen Van A"));
        when(repository.findByGoogleSubject("sub-new")).thenReturn(Optional.empty());
        when(refreshTokenService.issueFor(unconfirmed, "127.0.0.1", "ua", false)).thenReturn(SESSION);

        service.loginWithGoogle("code", "verifier", "nonce", false, "127.0.0.1", "ua");

        assertThat(unconfirmed.getUsername()).isNull();
        assertThat(unconfirmed.getFirstName()).isNull();
        assertThat(unconfirmed.getLastName()).isNull();
        assertThat(unconfirmed.getName()).isEqualTo("Nguyen Van A");
    }

    @Test
    void googleLoginWhoseEmailBelongsToASuspendedUnconfirmedAccountIsAccountInactive() {
        AppUser unconfirmed = unconfirmedMember("person@example.com");
        unconfirmed.changeStatus(UserStatus.SUSPENDED);
        givenGoogleIdentity("sub-new", "person@example.com");
        when(repository.findByGoogleSubject("sub-new")).thenReturn(Optional.empty());

        assertThat(googleLoginFails()).isEqualTo(OAuthFailure.ACCOUNT_INACTIVE);

        verifyOAuthFailureAudited(OAuthFailure.ACCOUNT_INACTIVE);
        assertThat(unconfirmed.getGoogleSubject()).isNull();
        assertThat(unconfirmed.isEmailConfirmed()).isFalse();
        verifyNoInteractions(events);
    }

    @Test
    void googleLoginOfANewEmailCreatesAGoogleOnlyMemberUnderTheRegistrationOwner() {
        AppUser owner = givenOwner(Role.ADMIN, UserStatus.ACTIVE);
        givenGoogleIdentity("sub-new", " New@Example.com ");
        when(repository.findByGoogleSubject("sub-new")).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(refreshTokenService.issueFor(any(), any(), any(), any(Boolean.class))).thenReturn(SESSION);

        AuthSession session = service.loginWithGoogle("code", "verifier", "nonce", false, "127.0.0.1", "ua");

        assertThat(session).isEqualTo(SESSION);
        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
        verify(repository).saveAndFlush(captor.capture());
        AppUser saved = captor.getValue();
        assertThat(saved.getOwnerId()).isEqualTo(owner.getId());
        assertThat(saved.getEmail()).isEqualTo("new@example.com");
        assertThat(saved.getGoogleSubject()).isEqualTo("sub-new");
        assertThat(saved.hasPassword()).isFalse();
        assertThat(saved.getRole()).isEqualTo(Role.MEMBER);
        verify(registrationRateLimiter).acquire("127.0.0.1", "new@example.com");
        verify(audit).write(eq(AuthEventType.REGISTRATION_ACCEPTED), eq(saved.getId()), any(), any(), any());
        verify(audit).write(eq(AuthEventType.OAUTH_LOGIN_SUCCESS), eq(saved.getId()), any(), any(), any());
    }

    @Test
    void aGoogleSignUpTakesItsNameFromTheTokenElseTheEmail() {
        givenOwner(Role.ADMIN, UserStatus.ACTIVE);
        when(googleOAuthClient.exchangeAndVerify("code", "verifier", "nonce"))
                .thenReturn(new GoogleIdTokenClaims("sub-named", "named@example.com", true, "Tran Thi B"))
                .thenReturn(new GoogleIdTokenClaims("sub-anon", "anon.person@example.com", true, null));
        when(repository.findByGoogleSubject(any())).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(refreshTokenService.issueFor(any(), any(), any(), any(Boolean.class))).thenReturn(SESSION);

        service.loginWithGoogle("code", "verifier", "nonce", false, "127.0.0.1", "ua");
        service.loginWithGoogle("code", "verifier", "nonce", false, "127.0.0.1", "ua");

        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
        verify(repository, times(2)).saveAndFlush(captor.capture());
        assertThat(captor.getAllValues()).extracting(AppUser::getName).containsExactly("Tran Thi B", "anon.person");
    }

    @Test
    void googleSignUpIsClosedWhenRegistrationIsClosed() {
        givenGoogleIdentity("sub-new", "new@example.com");
        when(repository.findByGoogleSubject("sub-new")).thenReturn(Optional.empty());
        when(repository.findByEmail(OWNER_EMAIL)).thenReturn(Optional.empty());

        assertThat(googleLoginFails()).isEqualTo(OAuthFailure.REGISTRATION_CLOSED);

        verifyOAuthFailureAudited(OAuthFailure.REGISTRATION_CLOSED);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void googleSignUpIsRateLimitedLikeRegistration() {
        givenGoogleIdentity("sub-new", "new@example.com");
        when(repository.findByGoogleSubject("sub-new")).thenReturn(Optional.empty());
        doThrow(new RegistrationRateLimitedException(Duration.ofMinutes(1))).when(registrationRateLimiter)
                .acquire("127.0.0.1", "new@example.com");

        assertThat(googleLoginFails()).isEqualTo(OAuthFailure.RATE_LIMITED);

        verify(audit).write(eq(AuthEventType.REGISTRATION_REJECTED_RATE_LIMITED), isNull(), any(), any(), any());
        verifyOAuthFailureAudited(OAuthFailure.RATE_LIMITED);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void googleSignUpLosingARaceOnTheEmailRequiresALink() {
        givenOwner(Role.ADMIN, UserStatus.ACTIVE);
        givenGoogleIdentity("sub-new", "new@example.com");
        when(repository.findByGoogleSubject("sub-new")).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("uq_app_user_email"));

        assertThat(googleLoginFails()).isEqualTo(OAuthFailure.LINK_REQUIRED);
        verifyOAuthFailureAudited(OAuthFailure.LINK_REQUIRED);
    }

    @Test
    void aGoogleExchangeOrVerificationFailureIsFailedAndNothingIsRead() {
        when(googleOAuthClient.exchangeAndVerify("code", "verifier", "nonce"))
                .thenThrow(new OAuthVerificationException("token endpoint unreachable"));

        assertThat(googleLoginFails()).isEqualTo(OAuthFailure.FAILED);

        verifyOAuthFailureAudited(OAuthFailure.FAILED);
        verifyNoInteractions(repository);
    }

    @Test
    void recordOAuthFailureWritesTheReasonCode() {
        service.recordOAuthFailure(OAuthFailure.INVALID_STATE, "127.0.0.1", "ua");

        verify(audit).write(eq(AuthEventType.OAUTH_LOGIN_FAILED), isNull(), eq("127.0.0.1"), eq("ua"),
                eq(Map.of("reason", "invalid_state")));
    }

    @Test
    void refreshRotatedAuditsTheRotationAndReturnsTheSession() {
        UUID userId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();
        when(refreshTokenService.rotate("raw", "127.0.0.1", "ua"))
                .thenReturn(new RotationResult.Rotated(SESSION, userId, familyId));

        Optional<AuthSession> session = service.refresh("raw", "127.0.0.1", "ua");

        assertThat(session).hasValue(SESSION);
        verify(audit).write(eq(AuthEventType.REFRESH_TOKEN_ROTATED), eq(userId), eq("127.0.0.1"), eq("ua"),
                eq(Map.of("userId", userId.toString(), "familyId", familyId.toString())));
    }

    @Test
    void refreshReuseIsEmptyAndAuditedAsReuse() {
        UUID userId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();
        when(refreshTokenService.rotate("raw", "127.0.0.1", "ua"))
                .thenReturn(new RotationResult.Rejected(RotationResult.Reason.REUSED, userId, familyId));

        assertThat(service.refresh("raw", "127.0.0.1", "ua")).isEmpty();

        verify(audit).write(eq(AuthEventType.REFRESH_TOKEN_REUSE_DETECTED), eq(userId), any(), any(), any());
    }

    @Test
    void refreshOtherRejectionsAreEmptyWithoutAnAuditRow() {
        when(refreshTokenService.rotate("raw", "127.0.0.1", "ua"))
                .thenReturn(new RotationResult.Rejected(RotationResult.Reason.UNKNOWN_TOKEN, null, null));

        assertThat(service.refresh("raw", "127.0.0.1", "ua")).isEmpty();

        verifyNoInteractions(audit);
    }

    @Test
    void logoutEndsTheSessionAndAuditsIt() {
        UUID userId = UUID.randomUUID();
        when(refreshTokenService.endSession("raw")).thenReturn(Optional.of(userId));

        service.logout(Optional.of("raw"), "127.0.0.1", "ua");

        verify(audit).write(eq(AuthEventType.LOGOUT), eq(userId), eq("127.0.0.1"), eq("ua"), any());
    }

    @Test
    void logoutWithoutATokenOrWithAnUnknownOneDoesNothing() {
        when(refreshTokenService.endSession("unknown")).thenReturn(Optional.empty());

        service.logout(Optional.empty(), "127.0.0.1", "ua");
        service.logout(Optional.of("unknown"), "127.0.0.1", "ua");

        verifyNoInteractions(audit);
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
