package com.flowcortex.user.application;

import com.flowcortex.shared.web.AccountLockedException;
import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.user.api.AuthService;
import com.flowcortex.user.api.AuthSession;
import com.flowcortex.user.api.CurrentUser;
import com.flowcortex.user.api.OAuthFailure;
import com.flowcortex.user.api.OAuthLoginException;
import com.flowcortex.user.domain.AppUser;
import com.flowcortex.user.domain.Role;
import com.flowcortex.user.domain.UserStatus;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

    AuthServiceImpl(AppUserRepository userRepository, PasswordEncoder passwordEncoder,
            RefreshTokenService refreshTokenService, LoginAttemptRecorder loginAttempts,
            LoginRateLimiter loginRateLimiter, RegistrationRateLimiter registrationRateLimiter,
            GoogleOAuthClient googleOAuthClient, AuditEventWriter audit, RegistrationProperties registration,
            Clock clock) {
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
    }

    @Override
    public AuthSession register(String rawEmail, String rawPassword, String name, boolean rememberMe, String ip,
            String userAgent) {
        String email = normalizeEmail(rawEmail);
        String agent = truncate(userAgent);
        try {
            registrationRateLimiter.acquire(ip, email);
        } catch (RegistrationRateLimitedException ex) {
            audit.write(AuthEventType.REGISTRATION_REJECTED_RATE_LIMITED, null, ip, agent, Map.of());
            throw ex;
        }
        AppUser owner = requireRegistrationOwner();
        AppUser user = new AppUser(UUID.randomUUID(), owner.getId(), email, passwordEncoder.encode(rawPassword), name,
                Role.MEMBER, UserStatus.ACTIVE);
        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException(new LocalizedMessage("user.emailTaken", email));
        }
        AuthSession session = refreshTokenService.issueFor(user, ip, agent, rememberMe);
        audit.write(AuthEventType.REGISTRATION_ACCEPTED, user.getId(), ip, agent,
                AuthEventType.userMetadata(user.getId()));
        return session;
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
        AppUser user = findOrCreateGoogleUser(claims.sub(), email, ip, agent);
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

    /** Accounts are matched by Google subject only: an email that already has an account is never linked. */
    private AppUser findOrCreateGoogleUser(String subject, String email, String ip, String agent) {
        Optional<AppUser> bySubject = userRepository.findByGoogleSubject(subject);
        if (bySubject.isPresent()) {
            AppUser user = bySubject.get();
            if (!AccountStatusGuard.canSignIn(user, Instant.now(clock))) {
                throw refuse(OAuthFailure.ACCOUNT_INACTIVE, user.getId(), ip, agent, Map.of());
            }
            return user;
        }
        Map<String, Object> emailMetadata = AuthEventType.metadata("email", email);
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw refuse(OAuthFailure.LINK_REQUIRED, null, ip, agent, emailMetadata);
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
        AppUser fresh = new AppUser(UUID.randomUUID(), owner.getId(), email, "", "Google User", Role.MEMBER,
                UserStatus.ACTIVE, false);
        fresh.linkGoogle(subject);
        try {
            userRepository.saveAndFlush(fresh);
        } catch (DataIntegrityViolationException ex) {
            throw refuse(OAuthFailure.LINK_REQUIRED, null, ip, agent, emailMetadata);
        }
        audit.write(AuthEventType.REGISTRATION_ACCEPTED, fresh.getId(), ip, agent,
                AuthEventType.userMetadata(fresh.getId()));
        return fresh;
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
