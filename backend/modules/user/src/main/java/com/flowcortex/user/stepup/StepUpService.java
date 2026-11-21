package com.flowcortex.user.stepup;

import com.flowcortex.shared.ratelimit.RateLimiter;
import com.flowcortex.shared.ratelimit.RateLimiters;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.user.api.StepUpProofs;
import com.flowcortex.user.api.StepUpPurpose;
import com.flowcortex.user.api.StepUpRequiredException;
import com.flowcortex.user.application.AuditEventWriter;
import com.flowcortex.user.application.AuthEventType;
import com.flowcortex.user.application.OpaqueTokens;
import com.flowcortex.user.domain.AppUser;
import com.flowcortex.user.infrastructure.AppUserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mints a step-up proof for a signed-in user who re-enters the account password, and spends it for
 * the action that demands it. A wrong step-up password never touches the lockout counter: the
 * caller already holds a session, and a stolen session must not be able to lock the owner out.
 * Neither the password nor the proof reaches an audit row, a log line or an error message.
 */
@Service
class StepUpService implements StepUpProofs {

    private static final Logger LOG = LoggerFactory.getLogger(StepUpService.class);

    private final StepUpProofRepository proofs;
    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AuditEventWriter audit;
    private final RateLimiter attempts;
    private final Clock clock;

    StepUpService(StepUpProofRepository proofs, AppUserRepository users, PasswordEncoder passwordEncoder,
            AuditEventWriter audit, RateLimiters rateLimiters, Clock clock) {
        this.proofs = proofs;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.attempts = rateLimiters.named(StepUpRateLimits.STEP_UP_USER);
        this.clock = clock;
    }

    record MintedProof(String proof, Instant expiresAt) {
    }

    /**
     * Counts the attempt first, then checks the password; a refusal is audited before it is thrown.
     *
     * @throws StepUpRateLimitedException over the per-user limit, before the password is compared
     * @throws StepUpPasswordRequiredException when the account has no password
     * @throws StepUpInvalidPasswordException when the password does not match
     */
    MintedProof mint(UUID userId, String password, StepUpPurpose purpose, String ip, String userAgent) {
        Optional<Duration> wait = attempts.tryAcquire(userId.toString());
        if (wait.isPresent()) {
            record(AuthEventType.STEP_UP_RATE_LIMITED, userId, purpose, ip, userAgent);
            throw new StepUpRateLimitedException(wait.get());
        }
        AppUser user = users.findById(userId)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("user.notFound", userId)));
        if (!user.hasPassword()) {
            record(AuthEventType.STEP_UP_FAILED_PASSWORD_REQUIRED, userId, purpose, ip, userAgent);
            throw new StepUpPasswordRequiredException();
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            record(AuthEventType.STEP_UP_FAILED_INVALID_PASSWORD, userId, purpose, ip, userAgent);
            throw new StepUpInvalidPasswordException();
        }
        Instant now = clock.instant();
        String proof = OpaqueTokens.generateRawToken();
        Instant expiresAt = now.plus(purpose.ttl());
        proofs.save(new StepUpProof(UUID.randomUUID(), userId, purpose.name(), OpaqueTokens.hash(proof), now,
                expiresAt));
        record(AuthEventType.STEP_UP_SUCCEEDED, userId, purpose, ip, userAgent);
        return new MintedProof(proof, expiresAt);
    }

    /** Own transaction, so the spend commits even when the refusal rolls back the caller's. */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = StepUpRequiredException.class)
    public void consume(UUID userId, StepUpPurpose purpose, String proof) {
        if (proof == null || proof.isBlank()) {
            throw new StepUpRequiredException();
        }
        Instant now = clock.instant();
        ConsumedProof spent = proofs.consume(OpaqueTokens.hash(proof), now).orElse(null);
        if (StepUpVerdict.of(spent, userId, purpose, now) == StepUpVerdict.REFUSED) {
            LOG.info("Step-up proof refused: userId={} purpose={} spent={}", userId, purpose, spent != null);
            throw new StepUpRequiredException();
        }
    }

    private void record(AuthEventType type, UUID userId, StepUpPurpose purpose, String ip, String userAgent) {
        audit.write(type, userId, ip, userAgent,
                AuthEventType.metadata("userId", userId.toString(), "purpose", purpose.name()));
    }
}
