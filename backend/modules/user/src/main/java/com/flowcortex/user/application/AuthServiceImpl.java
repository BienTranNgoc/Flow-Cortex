package com.flowcortex.user.application;

import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.user.api.AuthService;
import com.flowcortex.user.api.CurrentUser;
import com.flowcortex.user.api.TokenPairResponse;
import com.flowcortex.user.domain.AppUser;
import com.flowcortex.user.domain.Role;
import com.flowcortex.user.domain.UserStatus;
import com.flowcortex.user.infrastructure.AppUserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Implementation of {@link AuthService}. Login, register, Google OAuth,
 * refresh, logout — all return a {@link TokenPair} so the access JWT and the
 * stateful refresh token travel together.
 */
@Service
class AuthServiceImpl implements AuthService {

    private static final Logger LOG = LoggerFactory.getLogger(AuthServiceImpl.class);

    private static final Duration LOCKOUT_DURATION = Duration.ofMinutes(15);

    private static final int MAX_FAILED_LOGIN_ATTEMPTS = 5;

    private static final String REGISTRATION_CLOSED = "Self-registration is not available";

    private static final String INVALID_CREDENTIALS = "Invalid email or password";

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenService accessTokenService;
    private final RefreshTokenService refreshTokenService;
    private final GoogleOAuthClient googleOAuthClient;
    private final OAuthStateStore oauthStateStore;
    private final GoogleOAuthProperties oauthProperties;
    private final AuditEventWriter audit;
    private final RegistrationProperties registration;
    private final Clock clock;

    AuthServiceImpl(AppUserRepository userRepository, PasswordEncoder passwordEncoder,
            AccessTokenService accessTokenService, RefreshTokenService refreshTokenService,
            GoogleOAuthClient googleOAuthClient, OAuthStateStore oauthStateStore,
            GoogleOAuthProperties oauthProperties, AuditEventWriter audit, RegistrationProperties registration,
            Clock clock) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.accessTokenService = accessTokenService;
        this.refreshTokenService = refreshTokenService;
        this.googleOAuthClient = googleOAuthClient;
        this.oauthStateStore = oauthStateStore;
        this.oauthProperties = oauthProperties;
        this.audit = audit;
        this.registration = registration;
        this.clock = clock;
    }

    @Override
    @Transactional
    public TokenPairResponse register(String email, String rawPassword, String name, boolean rememberMe, String ip,
            String userAgent) {
        AppUser owner = requireRegistrationOwner();
        AppUser user = new AppUser(UUID.randomUUID(), owner.getId(), email, passwordEncoder.encode(rawPassword), name,
                Role.MEMBER, UserStatus.ACTIVE);
        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException("A user with email '" + email + "' already exists");
        }
        return toResponse(refreshTokenService.issueFor(user, ip, truncate(userAgent), rememberMe));
    }

    @Override
    @Transactional
    public TokenPairResponse login(String email, String rawPassword, boolean rememberMe, String ip, String userAgent) {
        AppUser user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BadCredentialsException(INVALID_CREDENTIALS));
        AccountStatusGuard.requireActive(user);
        AccountStatusGuard.requireNotLocked(user);
        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            user.recordFailedLogin(MAX_FAILED_LOGIN_ATTEMPTS, LOCKOUT_DURATION, Instant.now(clock));
            throw new BadCredentialsException(INVALID_CREDENTIALS);
        }
        user.resetFailedLoginAttempts(Instant.now(clock));
        return toResponse(refreshTokenService.issueFor(user, ip, truncate(userAgent), rememberMe));
    }

    @Override
    @Transactional
    public CurrentUser updateOwnProfile(UUID userId, String name) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found: " + userId));
        AccountStatusGuard.requireActive(user);
        user.changeName(name);
        return AppUserMapper.toCurrentUser(user);
    }

    @Override
    @Transactional
    public TokenPairResponse loginWithGoogle(String code, String state, boolean rememberMe, String ip, String userAgent) {
        OAuthStateRecord record = oauthStateStore.consume(state)
                .orElseThrow(() -> {
                    audit.write(AuthEventType.OAUTH_LOGIN_FAILED_INVALID_STATE, null, ip, truncate(userAgent),
                            java.util.Map.of());
                    return new ConflictException("invalid_oauth_state");
                });
        GoogleIdTokenClaims claims = googleOAuthClient.exchangeAndVerify(code, record.redirectUri());
        AppUser user = findOrCreateGoogleUser(claims, ip, userAgent);
        audit.write(AuthEventType.OAUTH_LOGIN_SUCCESS, user.getId(), ip, truncate(userAgent),
                AuthEventType.metadata("email", claims.email()));
        return toResponse(refreshTokenService.issueFor(user, ip, truncate(userAgent), rememberMe));
    }

    @Override
    @Transactional
    public TokenPairResponse refresh(String rawToken, String ip, String userAgent) {
        return toResponse(refreshTokenService.rotate(rawToken, ip, truncate(userAgent)));
    }

    @Override
    @Transactional
    public void logout(UUID callerId, Optional<String> refreshToken, String ip, String userAgent) {
        boolean refreshRevoked = false;
        if (refreshToken.isPresent()) {
            Optional<com.flowcortex.user.domain.RefreshToken> token = refreshTokenService.findByRaw(refreshToken.get());
            if (token.isPresent()) {
                refreshTokenService.revoke(token.get(), Instant.now(clock));
                refreshRevoked = true;
            }
        }
        final boolean finalRefreshRevoked = refreshRevoked;
        userRepository.findById(callerId).ifPresent(user -> {
            user.bumpTokenVersion();
            audit.write(AuthEventType.LOGOUT, user.getId(), ip, truncate(userAgent),
                    AuthEventType.metadata("refreshTokenRevoked", finalRefreshRevoked));
        });
    }

    private AppUser findOrCreateGoogleUser(GoogleIdTokenClaims claims, String ip, String userAgent) {
        Optional<AppUser> bySubject = userRepository.findByGoogleSubject(claims.sub());
        if (bySubject.isPresent()) {
            return bySubject.get();
        }
        Optional<AppUser> byEmail = userRepository.findByEmail(claims.email());
        if (byEmail.isPresent()) {
            AppUser existing = byEmail.get();
            if (existing.hasPassword() && oauthProperties.requireExplicitLink()) {
                audit.write(AuthEventType.OAUTH_LINK_BLOCKED_PASSWORD_REQUIRED, existing.getId(), ip,
                        truncate(userAgent), AuthEventType.metadata("email", claims.email()));
                throw new GoogleLinkRequiredException(claims.email());
            }
            existing.linkGoogle(claims.sub());
            audit.write(AuthEventType.OAUTH_ACCOUNT_LINKED, existing.getId(), ip, truncate(userAgent),
                    AuthEventType.metadata("email", claims.email()));
            return existing;
        }
        AppUser fresh = new AppUser(UUID.randomUUID(), UUID.randomUUID(), claims.email(), "", "Google User",
                Role.MEMBER, UserStatus.ACTIVE, false);
        fresh.linkGoogle(claims.sub());
        userRepository.save(fresh);
        return fresh;
    }

    private AppUser requireRegistrationOwner() {
        String ownerEmail = registration.ownerEmail() == null ? "" : registration.ownerEmail().trim();
        if (ownerEmail.isEmpty()) {
            LOG.info("Registration refused: reason=owner_not_configured");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, REGISTRATION_CLOSED);
        }
        AppUser owner = userRepository.findByEmail(ownerEmail).orElse(null);
        String unusable = owner == null ? "owner_not_found"
                : owner.getRole() != Role.ADMIN ? "owner_not_admin"
                : owner.getStatus() != UserStatus.ACTIVE ? "owner_not_active"
                : null;
        if (unusable != null) {
            LOG.warn("Registration refused: reason={} (check flowcortex.registration.owner-email)", unusable);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, REGISTRATION_CLOSED);
        }
        return owner;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 512 ? value : value.substring(0, 512);
    }

    private static com.flowcortex.user.api.TokenPairResponse toResponse(TokenPair pair) {
        return new com.flowcortex.user.api.TokenPairResponse(pair.accessToken(), pair.refreshToken(),
                pair.accessTokenExpiresAt(), pair.refreshTokenExpiresAt());
    }
}
