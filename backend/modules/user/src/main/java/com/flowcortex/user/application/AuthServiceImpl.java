package com.flowcortex.user.application;

import com.flowcortex.shared.web.AccountLockedException;
import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.shared.web.UnprocessableEntityException;
import com.flowcortex.user.api.AuthService;
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
import com.flowcortex.user.emailconfirm.EmailConfirmationProperties;
import com.flowcortex.user.emailconfirm.EmailConfirmationService;
import com.flowcortex.user.emailconfirm.EmailNotConfirmedException;
import com.flowcortex.user.emailconfirm.RegistrationConflictException;
import com.flowcortex.user.infrastructure.AppUserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Implementation of {@link AuthService}. Login, register and refresh run outside a surrounding
 * transaction on purpose: every failure ends in an exception or a rejected result, and the
 * counters, revocations and audit rows those failures write must commit regardless, so each of
 * those writes owns its transaction.
 */
@Service
class AuthServiceImpl implements AuthService {

    private static final Logger LOG = LoggerFactory.getLogger(AuthServiceImpl.class);

    private static final String INVALID_CREDENTIALS = "Invalid email or password";

    private static final String CONFIRMATION_LINK_INVALID_CODE = "CONFIRMATION_LINK_INVALID";

    private static final String USERNAME_UNIQUE_INDEX = "uq_app_user_username";

    private static final LocalizedMessage CONFIRMATION_LINK_INVALID =
            new LocalizedMessage("auth.confirmationLinkInvalid");

    private static final LocalizedMessage PASSWORD_ALREADY_SET = new LocalizedMessage("auth.password.alreadySet");

    private static final LocalizedMessage PASSWORD_LINK_INVALID = new LocalizedMessage("auth.passwordLinkInvalid");

    private static final String PASSWORD_LINK_INVALID_CODE = "PASSWORD_LINK_INVALID";

    private static final LocalizedMessage PASSWORD_SETUP_TOKEN_INVALID =
            new LocalizedMessage("auth.passwordSetupExpired");

    private static final String PASSWORD_SETUP_TOKEN_INVALID_CODE = "PASSWORD_SETUP_TOKEN_INVALID";

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final LoginAttemptRecorder loginAttempts;
    private final LoginRateLimiter loginRateLimiter;
    private final RegistrationRateLimiter registrationRateLimiter;
    private final GoogleOAuthClient googleOAuthClient;
    private final AuditEventWriter audit;
    private final RegistrationProperties registration;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transaction;
    private final EmailConfirmationService emailConfirmation;
    private final EmailConfirmationProperties emailConfirmationProperties;

    AuthServiceImpl(AppUserRepository userRepository, PasswordEncoder passwordEncoder,
            RefreshTokenService refreshTokenService, LoginAttemptRecorder loginAttempts,
            LoginRateLimiter loginRateLimiter, RegistrationRateLimiter registrationRateLimiter,
            GoogleOAuthClient googleOAuthClient, AuditEventWriter audit, RegistrationProperties registration,
            Clock clock, ApplicationEventPublisher events, PlatformTransactionManager transactionManager,
            EmailConfirmationService emailConfirmation, EmailConfirmationProperties emailConfirmationProperties) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenService = refreshTokenService;
        this.loginAttempts = loginAttempts;
        this.loginRateLimiter = loginRateLimiter;
        this.registrationRateLimiter = registrationRateLimiter;
        this.googleOAuthClient = googleOAuthClient;
        this.audit = audit;
        this.registration = registration;
        this.clock = clock;
        this.events = events;
        this.transaction = new TransactionTemplate(transactionManager);
        this.emailConfirmation = emailConfirmation;
        this.emailConfirmationProperties = emailConfirmationProperties;
    }

    @Override
    public Optional<AuthSession> register(String rawEmail, String rawPassword, String firstName, String lastName,
            String username, boolean rememberMe, String ip, String userAgent) {
        String email = normalizeEmail(rawEmail);
        String agent = truncate(userAgent);
        try {
            registrationRateLimiter.acquire(ip, email);
        } catch (RegistrationRateLimitedException ex) {
            audit.write(AuthEventType.REGISTRATION_REJECTED_RATE_LIMITED, null, ip, agent, Map.of());
            throw ex;
        }
        AppUser owner = requireRegistrationOwner();
        boolean confirmationRequired = emailConfirmationProperties.enabled();
        NewRegistration details = new NewRegistration(owner.getId(), email, passwordEncoder.encode(rawPassword),
                firstName.trim(), lastName.trim(), username, confirmationRequired ? null : Instant.now(clock),
                confirmationRequired);
        Registered registered;
        try {
            registered = saveRegistration(details);
        } catch (DataIntegrityViolationException ex) {
            if (isUsernameTaken(ex)) {
                throw RegistrationConflictException.usernameTaken();
            }
            // A concurrent first registration of this email committed in between; this pass finds its
            // row and replaces or refuses it like any other existing account.
            try {
                registered = saveRegistration(details);
            } catch (DataIntegrityViolationException again) {
                throw isUsernameTaken(again) ? RegistrationConflictException.usernameTaken()
                        : RegistrationConflictException.emailTaken();
            }
        }
        AppUser user = registered.account();
        audit.write(AuthEventType.REGISTRATION_ACCEPTED, user.getId(), ip, agent,
                AuthEventType.userMetadata(user.getId()));
        if (confirmationRequired) {
            LOG.info("Registration accepted: outcome=confirmation_sent userId={} replacedUnconfirmed={}",
                    user.getId(), registered.replacedUnconfirmed());
            return Optional.empty();
        }
        // Replacing a suspended unconfirmed account confirms it; like a login, it opens no session.
        requireActive(user, ip, agent);
        return Optional.of(refreshTokenService.issueFor(user, ip, agent, rememberMe));
    }

    private record NewRegistration(UUID ownerId, String email, String passwordHash, String firstName,
            String lastName, String username, Instant emailConfirmedAt, boolean confirmationRequired) {
    }

    private record Registered(AppUser account, boolean replacedUnconfirmed) {
    }

    /**
     * Inserts the account, or replaces an unconfirmed one with the same email; its link (or, with
     * confirmation off, its activation event) commits with it.
     */
    private Registered saveRegistration(NewRegistration details) {
        return transaction.execute(status -> {
            Optional<AppUser> existing = userRepository.findIdByEmail(details.email())
                    .flatMap(userRepository::findByIdForUpdate);
            if (existing.isPresent()) {
                AppUser account = existing.get();
                if (account.isEmailConfirmed()) {
                    throw RegistrationConflictException.emailTaken();
                }
                account.replaceUnconfirmedRegistration(details.passwordHash(), details.firstName(),
                        details.lastName(), details.username(), details.emailConfirmedAt());
                userRepository.saveAndFlush(account);
                if (details.confirmationRequired()) {
                    emailConfirmation.reissue(account.getId(), account.getEmail());
                } else {
                    emailConfirmation.retireOpenLinks(account.getId());
                    if (account.getStatus() == UserStatus.ACTIVE) {
                        events.publishEvent(new UserActivatedEvent(account.getId()));
                    }
                }
                return new Registered(account, true);
            }
            AppUser user = AppUser.selfRegistered(UUID.randomUUID(), details.ownerId(), details.email(),
                    details.passwordHash(), details.firstName(), details.lastName(), details.username(),
                    details.emailConfirmedAt());
            insertAccount(user, details.confirmationRequired());
            return new Registered(user, false);
        });
    }

    @Override
    public ConfirmedEmailSession confirmEmail(String rawToken, String ip, String userAgent) {
        String agent = truncate(userAgent);
        OpenedLink opened = transaction.execute(status -> {
            Optional<EmailConfirmationService.Confirmed> spent = emailConfirmation.confirm(rawToken);
            spent.filter(EmailConfirmationService.Confirmed::firstConfirmation).ifPresent(first -> {
                AppUser account = first.account();
                refreshTokenService.revokeAllFor(account.getId());
                // A suspended account is activated by the admin who reactivates it, not by its link.
                if (account.getStatus() == UserStatus.ACTIVE) {
                    events.publishEvent(new UserActivatedEvent(account.getId()));
                }
            });
            return spent.map(confirmed -> withSetupToken(confirmed.account())).orElse(null);
        });
        if (opened == null) {
            throw new UnprocessableEntityException(CONFIRMATION_LINK_INVALID, CONFIRMATION_LINK_INVALID_CODE);
        }
        return signInFromLink(opened, ip, agent);
    }

    @Override
    public ConfirmedEmailSession openPasswordLink(String rawToken, String ip, String userAgent) {
        String agent = truncate(userAgent);
        OpenedLink opened = transaction.execute(status ->
                emailConfirmation.openPasswordLink(rawToken).map(this::withSetupToken).orElse(null));
        if (opened == null) {
            throw new UnprocessableEntityException(PASSWORD_LINK_INVALID, PASSWORD_LINK_INVALID_CODE);
        }
        return signInFromLink(opened, ip, agent);
    }

    /** In the link's transaction; a suspended account gets no setup token, and no session either. */
    private OpenedLink withSetupToken(AppUser account) {
        boolean needsToken = !account.hasPassword() && account.getStatus() == UserStatus.ACTIVE;
        return new OpenedLink(account, needsToken ? emailConfirmation.issuePasswordSetupToken(account.getId()) : null);
    }

    private record OpenedLink(AppUser account, String passwordSetupToken) {
    }

    private ConfirmedEmailSession signInFromLink(OpenedLink opened, String ip, String agent) {
        AppUser user = opened.account();
        requireNotLocked(user, ip, agent);
        requireActive(user, ip, agent);
        AuthSession session = refreshTokenService.issueFor(user, ip, agent, false);
        audit.write(AuthEventType.LOGIN_SUCCESS, user.getId(), ip, agent, AuthEventType.userMetadata(user.getId()));
        return new ConfirmedEmailSession(session, !user.hasPassword(), opened.passwordSetupToken());
    }

    @Override
    public AuthSession setPassword(UUID userId, PasswordSetup setup, String ip, String userAgent) {
        String agent = truncate(userAgent);
        String passwordHash = passwordEncoder.encode(setup.password());
        AppUser user;
        try {
            user = transaction.execute(status -> {
                AppUser account = userRepository.findByIdForUpdate(userId)
                        .orElseThrow(() -> new NotFoundException(new LocalizedMessage("user.notFound", userId)));
                AccountStatusGuard.requireActive(account);
                if (account.hasPassword()) {
                    LOG.info("Set password refused: outcome=already_set userId={}", userId);
                    throw new ConflictException(PASSWORD_ALREADY_SET);
                }
                if (!emailConfirmation.spendPasswordSetupToken(userId, setup.setupToken())) {
                    LOG.info("Set password refused: outcome=setup_token_invalid userId={}", userId);
                    throw new UnprocessableEntityException(PASSWORD_SETUP_TOKEN_INVALID,
                            PASSWORD_SETUP_TOKEN_INVALID_CODE);
                }
                account.setUpFirstPassword(passwordHash, setup.firstName().trim(), setup.lastName().trim(),
                        setup.username());
                userRepository.saveAndFlush(account);
                emailConfirmation.retireOpenLinks(userId);
                refreshTokenService.revokeAllFor(userId);
                emailConfirmation.mailPasswordSetNotice(userId, account.getEmail());
                return account;
            });
        } catch (DataIntegrityViolationException ex) {
            if (isUsernameTaken(ex)) {
                throw RegistrationConflictException.usernameTaken();
            }
            throw ex;
        }
        audit.write(AuthEventType.PASSWORD_SET, userId, ip, agent, AuthEventType.userMetadata(userId));
        LOG.info("Password set: userId={}", userId);
        return refreshTokenService.issueFor(user, ip, agent, false);
    }

    @Override
    public void resendConfirmation(String rawEmail, String ip, String userAgent) {
        String email = normalizeEmail(rawEmail);
        try {
            registrationRateLimiter.acquire(ip, email);
        } catch (RegistrationRateLimitedException ex) {
            LOG.info("email_confirmation outcome=resend_rate_limited");
            throw ex;
        }
        emailConfirmation.resend(email);
    }

    @Override
    public AuthSession login(String rawEmail, String rawPassword, boolean rememberMe, String ip, String userAgent) {
        loginRateLimiter.requireWithinLimit(ip);
        String email = normalizeEmail(rawEmail);
        String agent = truncate(userAgent);
        AppUser user = userRepository.findByEmail(email).orElse(null);
        if (user != null) {
            requireNotLocked(user, ip, agent);
        }
        if (user == null || !user.hasPassword() || !passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            loginRateLimiter.recordFailure(ip);
            if (user != null && user.hasPassword()) {
                loginAttempts.recordFailure(user.getId());
            }
            audit.write(AuthEventType.LOGIN_FAILED_BAD_CREDENTIALS, user == null ? null : user.getId(), ip, agent,
                    user == null ? AuthEventType.metadata("email", email) : Map.of());
            throw new BadCredentialsException(INVALID_CREDENTIALS);
        }
        requireActive(user, ip, agent);
        if (!user.isEmailConfirmed()) {
            LOG.info("Login refused: outcome=email_not_confirmed userId={}", user.getId());
            throw new EmailNotConfirmedException();
        }
        if (user.getFailedLoginAttempts() > 0 || user.getLockedUntil() != null) {
            loginAttempts.recordSuccess(user.getId());
        }
        AuthSession session = refreshTokenService.issueFor(user, ip, agent, rememberMe);
        audit.write(AuthEventType.LOGIN_SUCCESS, user.getId(), ip, agent, AuthEventType.userMetadata(user.getId()));
        return session;
    }

    /**
     * Runs before the password is compared: a locked account answers the same 423 for a right and a
     * wrong password, and the attempt neither counts nor extends the lock.
     */
    private void requireNotLocked(AppUser user, String ip, String agent) {
        try {
            AccountStatusGuard.requireNotLocked(user, Instant.now(clock));
        } catch (AccountLockedException ex) {
            audit.write(AuthEventType.LOGIN_FAILED_ACCOUNT_LOCKED, user.getId(), ip, agent,
                    AuthEventType.userMetadata(user.getId()));
            throw ex;
        }
    }

    /** Reached only with the right password, so the refusal reveals nothing a wrong password wouldn't. */
    private void requireActive(AppUser user, String ip, String agent) {
        try {
            AccountStatusGuard.requireActive(user);
        } catch (AccessDeniedException ex) {
            audit.write(AuthEventType.LOGIN_FAILED_ACCOUNT_SUSPENDED, user.getId(), ip, agent,
                    AuthEventType.userMetadata(user.getId()));
            throw ex;
        }
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    @Override
    @Transactional
    public CurrentUser updateOwnProfile(UUID userId, String name) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("user.notFound", userId)));
        AccountStatusGuard.requireActive(user);
        user.changeName(name);
        return AppUserMapper.toCurrentUser(user);
    }

    @Override
    public AuthSession loginWithGoogle(String code, String codeVerifier, String nonce, boolean rememberMe, String ip,
            String userAgent) {
        String agent = truncate(userAgent);
        GoogleIdTokenClaims claims;
        try {
            claims = googleOAuthClient.exchangeAndVerify(code, codeVerifier, nonce);
        } catch (OAuthVerificationException ex) {
            LOG.warn("Google sign-in failed: {}", ex.getMessage());
            throw refuse(OAuthFailure.FAILED, null, ip, agent, Map.of());
        }
        String email = normalizeEmail(claims.email());
        AppUser user = findOrCreateGoogleUser(claims.sub(), email, claims.name(), ip, agent);
        AuthSession session = refreshTokenService.issueFor(user, ip, agent, rememberMe);
        audit.write(AuthEventType.OAUTH_LOGIN_SUCCESS, user.getId(), ip, agent, AuthEventType.metadata("email", email));
        return session;
    }

    @Override
    public void recordOAuthFailure(OAuthFailure failure, String ip, String userAgent) {
        refuse(failure, null, ip, truncate(userAgent), Map.of());
    }

    @Override
    public Optional<AuthSession> refresh(String rawToken, String ip, String userAgent) {
        String agent = truncate(userAgent);
        RotationResult result = refreshTokenService.rotate(rawToken, ip, agent);
        if (result instanceof RotationResult.Rotated rotated) {
            audit.write(AuthEventType.REFRESH_TOKEN_ROTATED, rotated.userId(), ip, agent,
                    sessionMetadata(rotated.userId(), rotated.familyId()));
            return Optional.of(rotated.session());
        }
        RotationResult.Rejected rejected = (RotationResult.Rejected) result;
        LOG.info("Refresh token rejected: reason={}", rejected.reason());
        if (rejected.reason() == RotationResult.Reason.REUSED) {
            audit.write(AuthEventType.REFRESH_TOKEN_REUSE_DETECTED, rejected.userId(), ip, agent,
                    sessionMetadata(rejected.userId(), rejected.familyId()));
        }
        return Optional.empty();
    }

    @Override
    public void logout(Optional<String> refreshToken, String ip, String userAgent) {
        refreshToken.flatMap(refreshTokenService::endSession).ifPresent(userId ->
                audit.write(AuthEventType.LOGOUT, userId, ip, truncate(userAgent), AuthEventType.userMetadata(userId)));
    }

    private static Map<String, Object> sessionMetadata(UUID userId, UUID familyId) {
        return AuthEventType.metadata("userId", userId.toString(), "familyId", familyId.toString());
    }

    /**
     * Accounts are matched by Google subject. An email that already has a confirmed account is never
     * linked; one whose account is still unconfirmed is taken over (Google has verified the address).
     */
    private AppUser findOrCreateGoogleUser(String subject, String email, String googleName, String ip,
            String agent) {
        Optional<AppUser> bySubject = userRepository.findByGoogleSubject(subject);
        if (bySubject.isPresent()) {
            AppUser user = bySubject.get();
            if (!AccountStatusGuard.canSignIn(user, Instant.now(clock))) {
                throw refuse(OAuthFailure.ACCOUNT_INACTIVE, user.getId(), ip, agent, Map.of());
            }
            return user;
        }
        Map<String, Object> emailMetadata = AuthEventType.metadata("email", email);
        Optional<UUID> existing = userRepository.findIdByEmail(email);
        if (existing.isPresent()) {
            return claimUnconfirmedAccount(existing.get(), subject, googleName, ip, agent, emailMetadata);
        }
        try {
            registrationRateLimiter.acquire(ip, email);
        } catch (RegistrationRateLimitedException ex) {
            audit.write(AuthEventType.REGISTRATION_REJECTED_RATE_LIMITED, null, ip, agent, Map.of());
            throw refuse(OAuthFailure.RATE_LIMITED, null, ip, agent, emailMetadata);
        }
        final AppUser owner;
        try {
            owner = requireRegistrationOwner();
        } catch (ResponseStatusException ex) {
            throw refuse(OAuthFailure.REGISTRATION_CLOSED, null, ip, agent, emailMetadata);
        }
        AppUser fresh = AppUser.googleSignedUp(UUID.randomUUID(), owner.getId(), email, subject, googleName);
        try {
            transaction.executeWithoutResult(status -> insertAccount(fresh, false));
        } catch (DataIntegrityViolationException ex) {
            throw refuse(OAuthFailure.LINK_REQUIRED, null, ip, agent, emailMetadata);
        }
        audit.write(AuthEventType.REGISTRATION_ACCEPTED, fresh.getId(), ip, agent,
                AuthEventType.userMetadata(fresh.getId()));
        return fresh;
    }

    /**
     * Links the Google subject to an unconfirmed account, confirms it and drops its password, username
     * and names (the display name becomes the Google name), ends its sessions and retires its links, all
     * in one transaction: whoever registered the address without proving it keeps no way in and leaves
     * no identity behind.
     */
    private AppUser claimUnconfirmedAccount(UUID userId, String subject, String googleName, String ip, String agent,
            Map<String, Object> emailMetadata) {
        Claim claim;
        try {
            claim = transaction.execute(status -> {
                AppUser account = userRepository.findByIdForUpdate(userId).orElse(null);
                if (account == null || account.isEmailConfirmed()) {
                    return new Claim(null, OAuthFailure.LINK_REQUIRED);
                }
                // A lockout is not checked: it guards the password this claim drops.
                if (account.getStatus() != UserStatus.ACTIVE) {
                    return new Claim(account, OAuthFailure.ACCOUNT_INACTIVE);
                }
                account.linkGoogle(subject);
                account.confirmEmailDroppingUnprovenChoices(Instant.now(clock), googleName);
                userRepository.saveAndFlush(account);
                emailConfirmation.retireOpenLinks(userId);
                refreshTokenService.revokeAllFor(userId);
                events.publishEvent(new UserActivatedEvent(userId));
                return new Claim(account, null);
            });
        } catch (DataIntegrityViolationException ex) {
            // The subject was linked to another account in between.
            throw refuse(OAuthFailure.LINK_REQUIRED, null, ip, agent, emailMetadata);
        }
        if (claim.refusal() != null) {
            UUID refusedId = claim.account() == null ? null : claim.account().getId();
            throw refuse(claim.refusal(), refusedId, ip, agent,
                    claim.refusal() == OAuthFailure.LINK_REQUIRED ? emailMetadata : Map.of());
        }
        audit.write(AuthEventType.OAUTH_UNCONFIRMED_ACCOUNT_CLAIMED, userId, ip, agent,
                AuthEventType.userMetadata(userId));
        LOG.info("Google sign-in: outcome=unconfirmed_account_claimed userId={}", userId);
        return claim.account();
    }

    private record Claim(AppUser account, OAuthFailure refusal) {
    }

    /**
     * Joins the caller's transaction, in which the insert and what follows from it commit together:
     * the confirmation link when one is needed, otherwise the activation event.
     */
    private void insertAccount(AppUser user, boolean mailConfirmationLink) {
        userRepository.saveAndFlush(user);
        if (mailConfirmationLink) {
            emailConfirmation.issue(user.getId(), user.getEmail());
        } else {
            events.publishEvent(new UserActivatedEvent(user.getId()));
        }
    }

    private static boolean isUsernameTaken(DataIntegrityViolationException ex) {
        String message = NestedExceptionUtils.getMostSpecificCause(ex).getMessage();
        return message != null && message.contains(USERNAME_UNIQUE_INDEX);
    }

    private OAuthLoginException refuse(OAuthFailure failure, UUID userId, String ip, String agent,
            Map<String, Object> metadata) {
        Map<String, Object> withReason = new LinkedHashMap<>();
        withReason.put("reason", failure.code());
        withReason.putAll(metadata);
        audit.write(AuthEventType.OAUTH_LOGIN_FAILED, userId, ip, agent, withReason);
        return new OAuthLoginException(failure);
    }

    private AppUser requireRegistrationOwner() {
        String ownerEmail = registration.ownerEmail() == null ? "" : registration.ownerEmail().trim();
        if (ownerEmail.isEmpty()) {
            LOG.info("Registration refused: reason=owner_not_configured");
            throw registrationClosed();
        }
        AppUser owner = userRepository.findByEmail(ownerEmail).orElse(null);
        String unusable = owner == null ? "owner_not_found"
                : owner.getRole() != Role.ADMIN ? "owner_not_admin"
                : owner.getStatus() != UserStatus.ACTIVE ? "owner_not_active"
                : null;
        if (unusable != null) {
            LOG.warn("Registration refused: reason={} (check flowcortex.registration.owner-email)", unusable);
            throw registrationClosed();
        }
        return owner;
    }

    private static LocalizedStatusException registrationClosed() {
        return new LocalizedStatusException(HttpStatus.FORBIDDEN, new LocalizedMessage("auth.registrationClosed"));
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 512 ? value : value.substring(0, 512);
    }
}
