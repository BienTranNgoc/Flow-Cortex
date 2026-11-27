package com.flowcortex.user.application;

import com.flowcortex.shared.web.AccountLockedException;
import com.flowcortex.user.api.AuthSession;
import com.flowcortex.user.api.SessionOutcome;
import com.flowcortex.user.domain.AppUser;
import com.flowcortex.user.emailconfirm.EmailNotConfirmedException;
import com.flowcortex.user.infrastructure.AppUserRepository;
import com.flowcortex.user.totp.ChallengeOrigin;
import com.flowcortex.user.totp.LoginChallenge;
import com.flowcortex.user.totp.LoginChallengeRepository;
import com.flowcortex.user.totp.TotpProblems.ChallengeInvalid;
import com.flowcortex.user.totp.TotpProblems.InvalidCode;
import com.flowcortex.user.totp.TotpProperties;
import com.flowcortex.user.totp.TotpService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * The one place a verified first factor becomes a session. An account without TOTP gets its session straight
 * away; one with TOTP gets a challenge row and must complete it with a code. Completion re-runs the checks the
 * first factor ran, because the account may have been suspended, locked or taken off TOTP in the meantime.
 */
@Component
class SessionGate {

    private static final Logger LOG = LoggerFactory.getLogger(SessionGate.class);

    private final RefreshTokenService refreshTokenService;
    private final TotpService totp;
    private final LoginChallengeRepository challenges;
    private final TotpProperties properties;
    private final AuditEventWriter audit;
    private final AppUserRepository users;
    private final Clock clock;

    SessionGate(RefreshTokenService refreshTokenService, TotpService totp, LoginChallengeRepository challenges,
            TotpProperties properties, AuditEventWriter audit, AppUserRepository users, Clock clock) {
        this.refreshTokenService = refreshTokenService;
        this.totp = totp;
        this.challenges = challenges;
        this.properties = properties;
        this.audit = audit;
        this.users = users;
        this.clock = clock;
    }

    SessionOutcome open(AppUser user, boolean rememberMe, ChallengeOrigin origin, String ip, String agent) {
        if (!totp.isEnabled(user.getId())) {
            return new SessionOutcome.Opened(refreshTokenService.issueFor(user, ip, agent, rememberMe, null));
        }
        String raw = OpaqueTokens.generateRawToken();
        Duration ttl = properties.challengeTtl();
        challenges.insert(user.getId(), OpaqueTokens.hash(raw), rememberMe, origin, Instant.now(clock).plus(ttl));
        LOG.info("auth.totp op=challenge outcome=issued origin={} user={}", origin, user.getId());
        return new SessionOutcome.Challenged(raw, ttl);
    }

    /**
     * Spends the challenge behind {@code rawChallenge} for a valid code and opens the session its first factor
     * asked for, marked as having passed a second factor. A wrong code counts against the challenge, which
     * dies at the configured number of them.
     * @throws ChallengeInvalid when the challenge is unknown, expired, spent or dead, or its account no longer
     *         has TOTP on
     */
    AuthSession complete(String rawChallenge, String code, String ip, String agent) {
        Instant now = Instant.now(clock);
        int maxWrongCodes = properties.challengeMaxWrongCodes();
        LoginChallenge challenge = challenges.findLive(OpaqueTokens.hash(rawChallenge), now, maxWrongCodes)
                .orElseThrow(ChallengeInvalid::new);
        AppUser user = users.findById(challenge.userId()).orElseThrow(ChallengeInvalid::new);
        requireNotLocked(user, ip, agent);
        requireActive(user, ip, agent);
        if (challenge.origin() != ChallengeOrigin.GOOGLE && !user.isEmailConfirmed()) {
            LOG.info("Login refused: outcome=email_not_confirmed userId={}", user.getId());
            throw new EmailNotConfirmedException();
        }
        try {
            totp.verifyForSignIn(user.getId(), code, ip, agent);
        } catch (InvalidCode ex) {
            challenges.recordWrongCode(challenge.id(), now, maxWrongCodes);
            throw ex;
        }
        if (!challenges.spend(challenge.id(), now, maxWrongCodes)) {
            throw new ChallengeInvalid();
        }
        AuthSession session = refreshTokenService.issueFor(user, ip, agent, challenge.rememberMe(), now);
        if (challenge.origin() == ChallengeOrigin.GOOGLE) {
            audit.write(AuthEventType.OAUTH_LOGIN_SUCCESS, user.getId(), ip, agent,
                    AuthEventType.metadata("email", user.getEmail()));
        } else {
            audit.write(AuthEventType.LOGIN_SUCCESS, user.getId(), ip, agent, AuthEventType.userMetadata(user.getId()));
        }
        return session;
    }

    /**
     * Runs before the password is compared: a locked account answers the same 423 for a right and a
     * wrong password, and the attempt neither counts nor extends the lock.
     */
    void requireNotLocked(AppUser user, String ip, String agent) {
        try {
            AccountStatusGuard.requireNotLocked(user, Instant.now(clock));
        } catch (AccountLockedException ex) {
            audit.write(AuthEventType.LOGIN_FAILED_ACCOUNT_LOCKED, user.getId(), ip, agent,
                    AuthEventType.userMetadata(user.getId()));
            throw ex;
        }
    }

    /** Reached only after the first factor was proven, so the refusal reveals nothing a wrong one wouldn't. */
    void requireActive(AppUser user, String ip, String agent) {
        try {
            AccountStatusGuard.requireActive(user);
        } catch (AccessDeniedException ex) {
            audit.write(AuthEventType.LOGIN_FAILED_ACCOUNT_SUSPENDED, user.getId(), ip, agent,
                    AuthEventType.userMetadata(user.getId()));
            throw ex;
        }
    }
}
