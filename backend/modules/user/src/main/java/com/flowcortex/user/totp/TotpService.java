package com.flowcortex.user.totp;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.user.api.AuthSession;
import com.flowcortex.user.application.AuditEventWriter;
import com.flowcortex.user.application.AuthEventType;
import com.flowcortex.user.application.RefreshTokenService;
import com.flowcortex.user.application.RoleCatalog;
import com.flowcortex.user.domain.AppUser;
import com.flowcortex.user.infrastructure.AppUserRepository;
import com.flowcortex.user.totp.RecoveryCodeRepository.KeyedHash;
import com.flowcortex.user.totp.TotpProblems.AlreadyEnabled;
import com.flowcortex.user.totp.TotpProblems.ChallengeInvalid;
import com.flowcortex.user.totp.TotpProblems.InvalidCode;
import com.flowcortex.user.totp.TotpProblems.Locked;
import com.flowcortex.user.totp.TotpProblems.NotEnabled;
import com.flowcortex.user.totp.TotpProblems.NotSetUp;
import com.flowcortex.user.totp.TotpProblems.RequiredForRole;
import com.flowcortex.user.totp.TotpProblems.Unavailable;
import com.flowcortex.user.totp.TotpSecretCipher.TotpSecretUnreadableException;
import com.flowcortex.user.totp.UserTotpRepository.FailureCount;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The second factor of an account: enrolment, recovery codes, and the one check every code goes through.
 * A check runs in a fixed order, account lock, code shape, secret decrypt, match, claim, so a locked
 * account examines nothing and counts nothing, and an unreadable secret answers 503 without counting.
 * Every check of a code against an enabled account runs under that account's row lock in a transaction
 * of its own, so concurrent guesses are examined one at a time and the one after a lock sees it. A wrong
 * code is counted in that transaction, which commits before the refusal is thrown.
 */
@Service
public class TotpService {

    private static final Logger LOG = LoggerFactory.getLogger(TotpService.class);

    /** Which kind of code opened a sign-in. */
    public enum Method { TOTP, RECOVERY_CODE }

    /** {@code lockedUntil} is null unless the second factor is locked now. */
    public record Status(boolean enabled, boolean pending, boolean required, int recoveryCodesLeft,
            Instant lockedUntil) {
    }

    /** The secret is shown once, here, and never returned again. */
    public record Setup(String secret, String otpauthUri, String qrDataUrl) {
    }

    /** The recovery codes are shown once; {@code session} is the account's one session, marked as verified. */
    public record EnableResult(List<String> recoveryCodes, AuthSession session) {
    }

    private final UserTotpRepository totps;
    private final RecoveryCodeRepository recoveryCodes;
    private final LoginChallengeRepository challenges;
    private final TotpSecretCipher cipher;
    private final TotpProperties properties;
    private final TotpRequirement requirement;
    private final RoleCatalog roles;
    private final AppUserRepository users;
    private final RefreshTokenService sessions;
    private final AuditEventWriter audit;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final SecureRandom random = new SecureRandom();

    TotpService(UserTotpRepository totps, RecoveryCodeRepository recoveryCodes, LoginChallengeRepository challenges,
            TotpSecretCipher cipher, TotpProperties properties, TotpRequirement requirement, RoleCatalog roles,
            AppUserRepository users, RefreshTokenService sessions,
            AuditEventWriter audit, Clock clock, PlatformTransactionManager transactionManager) {
        this.totps = totps;
        this.recoveryCodes = recoveryCodes;
        this.challenges = challenges;
        this.cipher = cipher;
        this.properties = properties;
        this.requirement = requirement;
        this.roles = roles;
        this.users = users;
        this.sessions = sessions;
        this.audit = audit;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public Status status(UUID userId) {
        AppUser user = requireUser(userId);
        UserTotp row = totps.findByUserId(userId).orElse(null);
        boolean enabled = row != null && row.isEnabled();
        Instant now = now();
        Instant lockedUntil = row != null && row.isLockedAt(now) ? row.lockedUntil() : null;
        return new Status(enabled, row != null && !enabled, isRequired(user.getRole().code()),
                enabled ? recoveryCodes.countFor(userId) : 0, lockedUntil);
    }

    public boolean isEnabled(UUID userId) {
        return totps.isEnabled(userId);
    }

    /** Console accounts must keep TOTP on while the requirement is switched on. */
    public boolean isRequired(String roleCode) {
        return requirement.adminRequired() && roles.require(roleCode).isAdminAccount();
    }

    /** Starts a setup, replacing an unconfirmed secret. The failure count and any lock are kept. */
    public Setup setup(UUID userId) {
        AppUser user = requireUser(userId);
        String secret = TotpCodes.newSecret(random);
        if (totps.savePending(userId, cipher.encrypt(userId, secret)) == 0) {
            throw refused("setup", "already_enabled", userId, new AlreadyEnabled());
        }
        String uri = otpauthUri(user.getEmail(), secret);
        LOG.info("auth.totp op=setup outcome=started user={}", userId);
        return new Setup(secret, uri, TotpQr.dataUrl(uri));
    }

    /**
     * Confirms the pending secret with a code, stores the recovery codes and replaces every session of the
     * account with one that passed TOTP, keeping the remember-me of the session whose refresh token the
     * request carried. Of several concurrent calls one wins.
     */
    public EnableResult enable(UUID userId, String code, String ip, String agent, String presentedRefreshToken) {
        AppUser user = requireUser(userId);
        UserTotp row = totps.findByUserId(userId)
                .orElseThrow(() -> refused("enable", "not_set_up", userId, new NotSetUp()));
        if (row.isEnabled()) {
            throw refused("enable", "already_enabled", userId, new AlreadyEnabled());
        }
        requireNotLocked(row, "enable");
        long step = stepOf(userId, row, code, "enable")
                .orElseThrow(() -> wrongCode(userId, "enable", ip, agent));
        boolean rememberMe = presentedRefreshToken != null && sessions.rememberMeOf(userId, presentedRefreshToken);
        Instant now = now();

        EnableResult result = transaction.execute(status -> {
            if (totps.enable(userId, step) == 0) {
                throw totps.findByUserId(userId).isPresent() ? new AlreadyEnabled() : new NotSetUp();
            }
            totps.resetFailures(userId);
            List<String> codes = RecoveryCodes.generate(random);
            recoveryCodes.replaceAll(userId, hashed(codes));
            sessions.revokeAllFor(userId);
            return new EnableResult(codes, sessions.issueFor(user, ip, agent, rememberMe, now));
        });
        audit.write(AuthEventType.TOTP_ENABLED, userId, ip, agent, AuthEventType.userMetadata(userId));
        LOG.info("auth.totp op=enable outcome=enabled user={}", userId);
        return result;
    }

    /**
     * Turns TOTP off for an account that holds a valid TOTP or recovery code. A console account is refused
     * first, before any code is examined, while the requirement is on.
     */
    public void disable(UUID userId, String code, String ip, String agent) {
        AppUser user = requireUser(userId);
        requireEnabled(userId, "disable");
        if (isRequired(user.getRole().code())) {
            throw refused("disable", "required_for_role", userId, new RequiredForRole());
        }
        verify(userId, code, true, "disable", ip, agent);
        deleteFor(userId);
        audit.write(AuthEventType.TOTP_DISABLED, userId, ip, agent, AuthEventType.userMetadata(userId));
        LOG.info("auth.totp op=disable outcome=disabled user={}", userId);
    }

    /** Replaces every recovery code with ten new ones; only a TOTP code authorizes it, not a recovery code. */
    public List<String> regenerate(UUID userId, String code, String ip, String agent) {
        requireUser(userId);
        requireEnabled(userId, "regenerate");
        verify(userId, code, false, "regenerate", ip, agent);
        List<String> codes = RecoveryCodes.generate(random);
        recoveryCodes.replaceAll(userId, hashed(codes));
        audit.write(AuthEventType.TOTP_RECOVERY_CODES_REGENERATED, userId, ip, agent,
                AuthEventType.userMetadata(userId));
        LOG.info("auth.totp op=regenerate outcome=regenerated user={}", userId);
        return codes;
    }

    /**
     * Checks the code a sign-in challenge carries.
     * @throws ChallengeInvalid when the account no longer has TOTP on
     */
    public Method verifyForSignIn(UUID userId, String code, String ip, String agent) {
        return verify(userId, code, true, "sign_in", ip, agent);
    }

    /**
     * Removes the account's secret, lock state, recovery codes and live sign-in challenges in one
     * transaction (joining the caller's); true when it had a secret.
     */
    public boolean deleteFor(UUID userId) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            boolean had = totps.delete(userId) > 0;
            recoveryCodes.deleteAll(userId);
            challenges.deleteLiveFor(userId);
            return had;
        }));
    }

    /** What one examination under the account's row lock decided, committed before the refusal is thrown. */
    private sealed interface Verdict {

        record Passed(Method method) implements Verdict {
        }

        /** A wrong code was counted; the failure count and any lock it caused are already committed. */
        record Counted(FailureCount failure) implements Verdict {
        }
    }

    private Method verify(UUID userId, String code, boolean allowRecovery, String op, String ip, String agent) {
        Verdict verdict = transaction.execute(status -> examine(userId, code, allowRecovery, op));
        return switch (verdict) {
            case Verdict.Passed passed -> {
                if (passed.method() == Method.RECOVERY_CODE) {
                    audit.write(AuthEventType.TOTP_RECOVERY_CODE_USED, userId, ip, agent,
                            AuthEventType.metadata("userId", userId.toString(), "op", op));
                    LOG.info("auth.totp op={} outcome=recovery_code_used user={}", op, userId);
                }
                yield passed.method();
            }
            case Verdict.Counted counted -> throw countedRefusal(counted.failure(), op, userId, ip, agent);
        };
    }

    /**
     * Holds the account's row lock from the lock check to the write that decides the outcome, so a request
     * that starts after a lock was set sees it and a code is spent by one request at a time. A refusal that
     * writes nothing (locked, not enabled, unreadable secret) throws and rolls back; a wrong code commits its
     * count and is thrown by the caller.
     */
    private Verdict examine(UUID userId, String code, boolean allowRecovery, String op) {
        UserTotp row = totps.findForUpdate(userId).filter(UserTotp::isEnabled)
                .orElseThrow(() -> notEnabled(op, userId));
        requireNotLocked(row, op);
        Instant now = now();
        if (TotpCodes.looksLikeCode(code)) {
            OptionalLong step = stepOf(userId, row, code, op);
            if (step.isPresent() && totps.claimStep(userId, step.getAsLong(), now) == 1) {
                totps.resetFailures(userId);
                return new Verdict.Passed(Method.TOTP);
            }
        } else if (allowRecovery && RecoveryCodes.looksLikeRecoveryCode(code)) {
            String normalized = RecoveryCodes.normalize(code);
            for (String keyId : cipher.keyIds()) {
                if (recoveryCodes.consume(userId, keyId, cipher.recoveryHash(keyId, normalized), now)) {
                    totps.resetFailures(userId);
                    return new Verdict.Passed(Method.RECOVERY_CODE);
                }
            }
        }
        return new Verdict.Counted(totps.recordFailure(userId, properties.maxFailedAttempts(),
                now.plus(properties.lockout()), now).orElseThrow(
                        () -> new IllegalStateException("user_totp row of " + userId + " locked under its own lock")));
    }

    /**
     * Decrypts the secret and matches the code against it; empty for a wrong code, including one that is not
     * six digits.
     * @throws Unavailable when the stored secret cannot be decrypted
     */
    private OptionalLong stepOf(UUID userId, UserTotp row, String code, String op) {
        if (!TotpCodes.looksLikeCode(code)) {
            return OptionalLong.empty();
        }
        String secret;
        try {
            secret = cipher.decrypt(userId, row.secretEncrypted());
        } catch (TotpSecretUnreadableException ex) {
            LOG.warn("auth.totp op={} outcome=secret_unreadable user={}", op, userId);
            throw new Unavailable();
        }
        return TotpCodes.match(secret, code, now());
    }

    /**
     * Counts one wrong code outside any transaction of this class and returns the refusal: a lock when this
     * was the one that locked the account.
     */
    private RuntimeException wrongCode(UUID userId, String op, String ip, String agent) {
        Instant now = now();
        FailureCount failure = totps.recordFailure(userId, properties.maxFailedAttempts(),
                now.plus(properties.lockout()), now).orElse(null);
        if (failure == null) {
            Instant lockedUntil = totps.findByUserId(userId).filter(row -> row.isLockedAt(now))
                    .map(UserTotp::lockedUntil).orElse(null);
            LOG.info("auth.totp op={} outcome={} user={}", op, lockedUntil == null ? "wrong_code" : "locked", userId);
            return lockedUntil == null ? new InvalidCode() : new Locked(lockedUntil);
        }
        return countedRefusal(failure, op, userId, ip, agent);
    }

    private RuntimeException countedRefusal(FailureCount failure, String op, UUID userId, String ip, String agent) {
        if ("sign_in".equals(op)) {
            audit.write(AuthEventType.LOGIN_FAILED_TOTP, userId, ip, agent, AuthEventType.userMetadata(userId));
        }
        if (failure.lockedUntil() != null) {
            audit.write(AuthEventType.TOTP_LOCKED, userId, ip, agent, AuthEventType.userMetadata(userId));
            LOG.warn("auth.totp op={} outcome=locked user={}", op, userId);
            return new Locked(failure.lockedUntil());
        }
        LOG.info("auth.totp op={} outcome=wrong_code user={}", op, userId);
        return new InvalidCode();
    }

    private void requireNotLocked(UserTotp row, String op) {
        if (row.isLockedAt(now())) {
            LOG.info("auth.totp op={} outcome=locked user={}", op, row.userId());
            throw new Locked(row.lockedUntil());
        }
    }

    private UserTotp requireEnabled(UUID userId, String op) {
        return totps.findByUserId(userId).filter(UserTotp::isEnabled).orElseThrow(() -> notEnabled(op, userId));
    }

    /** A sign-in whose account lost TOTP has a dead challenge; any other operation is simply not enabled. */
    private static RuntimeException notEnabled(String op, UUID userId) {
        RuntimeException problem = "sign_in".equals(op) ? new ChallengeInvalid() : new NotEnabled();
        return refused(op, "not_enabled", userId, problem);
    }

    private AppUser requireUser(UUID userId) {
        return users.findById(userId)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("user.notFound", userId)));
    }

    private List<KeyedHash> hashed(List<String> codes) {
        String keyId = cipher.currentKeyId();
        return codes.stream().map(code -> new KeyedHash(keyId, cipher.recoveryHash(keyId,
                RecoveryCodes.normalize(code)))).toList();
    }

    private String otpauthUri(String email, String secret) {
        String issuer = encode(properties.issuer());
        return "otpauth://totp/" + issuer + ":" + encode(email).replace("%40", "@") + "?secret=" + secret
                + "&issuer=" + issuer + "&algorithm=SHA1&digits=6&period=30";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static <T extends RuntimeException> T refused(String op, String outcome, UUID userId, T problem) {
        LOG.info("auth.totp op={} outcome={} user={}", op, outcome, userId);
        return problem;
    }

    private Instant now() {
        return Instant.now(clock);
    }
}
