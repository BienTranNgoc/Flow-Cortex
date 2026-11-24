package com.flowcortex.user.accountdeletion;

import com.flowcortex.settings.api.Settings;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.user.accountdeletion.AccountDeletionRequestRepository.Projection;
import com.flowcortex.user.accountdeletion.DeletionEligibility.Eligibility;
import com.flowcortex.user.api.StepUpProofs;
import com.flowcortex.user.api.StepUpPurpose;
import com.flowcortex.user.api.StepUpRequiredException;
import com.flowcortex.user.application.AccountCredentials;
import com.flowcortex.user.application.AuditEventWriter;
import com.flowcortex.user.application.AuthEventType;
import com.flowcortex.user.application.RoleCatalog;
import com.flowcortex.user.infrastructure.AppUserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A member's request to delete their own account and their cancel of it. The request refuses cheaply
 * first (switch, role, an existing request), then spends the step-up proof, then checks every
 * blocking condition, and only then writes: the projection, the request row and the revocation of
 * every credential commit together. Audit rows are written after the commit, through the fail-open
 * writer, so a failed audit never undoes a request and a rolled-back request leaves no event.
 */
@Service
class AccountDeletionService {

    private static final Logger LOG = LoggerFactory.getLogger(AccountDeletionService.class);

    /** The route's answer to a request; {@code state} is {@code PENDING}, or {@code EXPIRED} for a repeat past the deadline. */
    record Accepted(UUID requestId, AccountDeletionState state, Instant requestedAt, Instant scheduledAt) {
    }

    record Status(boolean enabled, int gracePeriodDays, AccountDeletionState state, Instant requestedAt,
            Instant scheduledAt) {
    }

    private final Settings settings;
    private final Clock clock;
    private final StepUpProofs stepUpProofs;
    private final DeletionEligibility eligibility;
    private final AccountDeletionRequestRepository requests;
    private final AccountCredentials credentials;
    private final AuditEventWriter audit;
    private final AppUserRepository users;
    private final RoleCatalog roles;
    private final TransactionTemplate transaction;

    AccountDeletionService(Settings settings, Clock clock, StepUpProofs stepUpProofs, DeletionEligibility eligibility,
            AccountDeletionRequestRepository requests, AccountCredentials credentials, AuditEventWriter audit,
            AppUserRepository users, RoleCatalog roles, PlatformTransactionManager transactionManager) {
        this.settings = settings;
        this.clock = clock;
        this.stepUpProofs = stepUpProofs;
        this.eligibility = eligibility;
        this.requests = requests;
        this.credentials = credentials;
        this.audit = audit;
        this.users = users;
        this.roles = roles;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    Status status(UUID userId) {
        Projection projection = requests.projection(userId).orElse(new Projection(null, null));
        AccountDeletionState state = AccountDeletionState.of(projection.requestedAt(), projection.scheduledAt(),
                clock.instant());
        boolean pending = state != AccountDeletionState.NONE;
        return new Status(enabled(), graceDays(), state, pending ? projection.requestedAt() : null,
                pending ? projection.scheduledAt() : null);
    }

    /**
     * @throws com.flowcortex.shared.web.LocalizedStatusException 503 {@code account.deletion.eligibilityUnavailable}
     *         when a condition cannot be read
     */
    Eligibility eligibility(UUID userId) {
        try {
            return eligibility.evaluate(userId);
        } catch (EligibilityUnavailableException e) {
            LOG.info("account.deletion outcome=unavailable user={}", userId);
            throw AccountDeletionProblems.eligibilityUnavailable();
        }
    }

    /**
     * Refuses in a fixed order: switch off (503), a role that is not an end user (409), a proof that
     * is missing or spent (403), an account with unfinished activity (409 or 503). An account that
     * already has a request gets that request back before the proof is read, and again when a refused
     * proof is the revoked one of a twin that made the request in between.
     */
    Accepted request(UUID userId, String stepUpProof, String ip, String userAgent) {
        if (!enabled()) {
            throw AccountDeletionProblems.turnedOff();
        }
        requireEndUser(userId);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (hasRequest(userId, now)) {
            return repeat(userId, now);
        }
        try {
            stepUpProofs.consume(userId, StepUpPurpose.ACCOUNT_DELETION, stepUpProof);
        } catch (StepUpRequiredException refused) {
            // A twin that committed after the check above revoked this caller's unspent proof with the account's credentials.
            if (hasRequest(userId, now)) {
                return repeat(userId, now);
            }
            throw refused;
        }
        Eligibility result = eligibility(userId);
        if (!result.eligible()) {
            LOG.info("account.deletion outcome=blocked user={} reasons={}", userId, result.reasons().size());
            throw AccountDeletionProblems.blocked(result.reasons());
        }

        Instant scheduledAt = now.plus(Duration.ofDays(graceDays()));
        AccountDeletionRequest created;
        try {
            created = transaction.execute(status -> create(userId, now, scheduledAt));
        } catch (DuplicateKeyException lost) {
            return repeat(userId, now);
        }
        if (created == null) {
            return repeat(userId, now);
        }
        audit.write(AuthEventType.ACCOUNT_DELETION_REQUESTED, userId, ip, userAgent, AuthEventType.metadata(
                "userId", userId.toString(), "requestId", created.id().toString(), "scheduledAt",
                scheduledAt.toString()));
        LOG.info("account.deletion outcome=requested user={}", userId);
        return new Accepted(created.id(), AccountDeletionState.PENDING, now, scheduledAt);
    }

    /**
     * Cancels the pending request without a proof: every session ended with the request, so a caller
     * that reaches this route signed in after it. A deadline that has passed is 422, no request 409.
     *
     * @return when the request was cancelled
     */
    Instant cancel(UUID userId, String ip, String userAgent) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Boolean cancelled = transaction.execute(status -> {
            if (requests.cancelPending(userId, now) == 0) {
                return false;
            }
            requests.clearProjection(userId);
            return true;
        });
        if (!Boolean.TRUE.equals(cancelled)) {
            Projection projection = requests.projection(userId).orElse(new Projection(null, null));
            AccountDeletionState state = AccountDeletionState.of(projection.requestedAt(), projection.scheduledAt(),
                    now);
            LOG.info("account.deletion outcome=cancel_refused user={} state={}", userId, state);
            throw state == AccountDeletionState.EXPIRED
                    ? AccountDeletionProblems.recoveryExpired()
                    : AccountDeletionProblems.notPending();
        }
        audit.write(AuthEventType.ACCOUNT_DELETION_CANCELLED, userId, ip, userAgent,
                AuthEventType.userMetadata(userId));
        LOG.info("account.deletion outcome=cancelled user={}", userId);
        return now;
    }

    /**
     * The three writes of one request. The projection update loses to a concurrent twin by matching no
     * row ({@code null}); the revocation runs last so only a failed commit can leave its audit rows behind.
     */
    private AccountDeletionRequest create(UUID userId, Instant now, Instant scheduledAt) {
        if (requests.setProjection(userId, now, scheduledAt) == 0) {
            return null;
        }
        UUID requestId = UUID.randomUUID();
        requests.insertPending(requestId, userId, now, scheduledAt);
        credentials.revokeAllFor(userId);
        return new AccountDeletionRequest(requestId, userId, AccountDeletionRequest.State.PENDING, now, scheduledAt,
                null);
    }

    private boolean hasRequest(UUID userId, Instant now) {
        Projection projection = requests.projection(userId).orElseThrow(() -> userGone(userId));
        return AccountDeletionState.of(projection.requestedAt(), projection.scheduledAt(), now)
                != AccountDeletionState.NONE;
    }

    /** The account's existing request, answered as if it had just been made; nothing is written. */
    private Accepted repeat(UUID userId, Instant now) {
        Optional<AccountDeletionRequest> existing = requests.findPending(userId);
        AccountDeletionRequest request = existing.orElseThrow(() -> new IllegalStateException(
                "Account " + userId + " carries a deletion projection but has no pending request row"));
        LOG.info("account.deletion outcome=repeat user={}", userId);
        return new Accepted(request.id(),
                AccountDeletionState.of(request.requestedAt(), request.scheduledAt(), now), request.requestedAt(),
                request.scheduledAt());
    }

    /** Only a role positively known as an end-user role may delete itself; an unknown code fails closed. */
    private void requireEndUser(UUID userId) {
        String roleCode = users.findRoleCodeById(userId).orElseThrow(() -> userGone(userId));
        if (roles.find(roleCode).filter(role -> !role.isAdminAccount()).isEmpty()) {
            throw AccountDeletionProblems.staffAccount();
        }
    }

    private boolean enabled() {
        return Boolean.TRUE.equals(settings.get(AccountDeletionSettingKeys.ENABLED).effective());
    }

    private int graceDays() {
        return settings.get(AccountDeletionSettingKeys.GRACE_PERIOD_DAYS).effective();
    }

    private static NotFoundException userGone(UUID userId) {
        return new NotFoundException(new LocalizedMessage("user.notFound", userId));
    }
}
