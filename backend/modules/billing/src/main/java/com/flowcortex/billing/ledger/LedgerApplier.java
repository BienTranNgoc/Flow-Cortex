package com.flowcortex.billing.ledger;

import com.flowcortex.billing.ledger.CallbackInbox.Claimed;
import com.flowcortex.billing.ledger.LedgerDecision.Applied;
import com.flowcortex.billing.ledger.LedgerDecision.Decision;
import com.flowcortex.billing.ledger.LedgerDecision.Refused;
import com.flowcortex.billing.ledger.LedgerDecision.Skipped;
import com.flowcortex.billing.ledger.LedgerDecision.TransactionSnapshot;
import com.flowcortex.billing.payment.PaymentProviderEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applies accepted provider callbacks to payments. Each callback is decided in one transaction that holds
 * its inbox row and then its payment row, so concurrent passes and instances decide a callback once and two
 * callbacks for one payment one after the other. A callback that fails for any reason but a refusal is held
 * back with a growing wait and the pass goes on, so one bad callback never stops the others.
 */
@Component
@EnableConfigurationProperties(LedgerApplyProperties.class)
public class LedgerApplier {

    private static final int MAX_CAUSE_DEPTH = 10;

    private enum Step { IDLE, HANDLED, ABORTED }

    private record Taken(Claimed claimed, Optional<PaymentProviderEvent> event, Decision decision, UUID paymentId) {
    }

    private final CallbackInbox inbox;
    private final PaymentTransactionRepository payments;
    private final LedgerTelemetry telemetry;
    private final LedgerApplyProperties properties;
    private final Clock clock;
    private final TransactionTemplate transactions;

    LedgerApplier(CallbackInbox inbox, PaymentTransactionRepository payments, LedgerTelemetry telemetry,
            LedgerApplyProperties properties, Clock clock, PlatformTransactionManager transactionManager) {
        this.inbox = inbox;
        this.payments = payments;
        this.telemetry = telemetry;
        this.properties = properties;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** One timed pass, unless the applier is switched off. */
    @Scheduled(fixedDelayString = "${flowcortex.billing.ledger.apply.interval:PT5S}")
    public void scheduled() {
        if (properties.enabled()) {
            applyPending();
        }
    }

    /** Decides up to the batch size of due callbacks, oldest first, and stops early when none is due. */
    public void applyPending() {
        long started = System.nanoTime();
        boolean worked = false;
        boolean aborted = false;
        for (int taken = 0; taken < properties.batchSize(); taken++) {
            Step step = applyNext();
            if (step == Step.IDLE) {
                break;
            }
            if (step == Step.ABORTED) {
                aborted = true;
                break;
            }
            worked = true;
        }
        String outcome = aborted ? "failed" : worked ? "worked" : "idle";
        telemetry.pass(outcome, Duration.ofNanos(System.nanoTime() - started));
    }

    private Step applyNext() {
        AtomicReference<Claimed> claimed = new AtomicReference<>();
        Taken taken;
        try {
            taken = transactions.execute(status -> decideNext(claimed));
        } catch (RuntimeException e) {
            return afterFailure(claimed.get(), e);
        }
        if (taken == null) {
            return Step.IDLE;
        }
        telemetry.decided(taken.claimed().id(), taken.claimed().provider(), taken.decision(), taken.event(),
                taken.paymentId());
        return Step.HANDLED;
    }

    /** Runs inside the callback's transaction; returns null when nothing is due. */
    private Taken decideNext(AtomicReference<Claimed> claimedOut) {
        Instant now = clock.instant();
        Optional<Claimed> next = inbox.claimNext(now);
        if (next.isEmpty()) {
            return null;
        }
        Claimed claimed = next.get();
        claimedOut.set(claimed);
        Optional<PaymentProviderEvent> event = inbox.readEvent(claimed.eventJson());
        Optional<TransactionSnapshot> snapshot = event.flatMap(
                stated -> payments.lockByOrderRef(claimed.provider(), stated.reference().operationRef()));
        Decision decision = LedgerDecision.decide(event, snapshot);
        UUID paymentId = snapshot.map(TransactionSnapshot::id).orElse(null);
        switch (decision) {
            case Applied applied -> {
                String resultCode = applied.facts().resultCode();
                if (payments.applyMove(paymentId, applied.from(), applied.to(), applied.facts(), now) != 1) {
                    throw new IllegalStateException("The payment no longer holds the status the decision read");
                }
                payments.insertHistory(paymentId, applied.from(), applied.to(), "provider_code:" + resultCode,
                        resultCode, claimed.id().toString(), applied.facts().providerTimestamp(), now);
                mark(claimed, "applied", "provider_code:" + resultCode, paymentId, now);
            }
            case Skipped skipped -> mark(claimed, "skipped", skipped.reason(), paymentId, now);
            case Refused refused -> mark(claimed, "refused", refused.reason(), paymentId, now);
        }
        return new Taken(claimed, event, decision, paymentId);
    }

    private void mark(Claimed claimed, String outcome, String reason, UUID paymentId, Instant now) {
        if (inbox.markDecided(claimed.id(), outcome, reason, paymentId, now) != 1) {
            throw new IllegalStateException("The callback was decided by someone else while it was held");
        }
    }

    /**
     * The callback's own transaction has rolled back. A failure before any callback was held, or because the
     * database cannot be reached, ends the pass and counts no attempt; any other failure counts one attempt
     * against the callback, in a transaction of its own, and the pass goes on.
     */
    private Step afterFailure(Claimed claimed, RuntimeException failure) {
        telemetry.applyFailed(claimed == null ? null : claimed.id(), failure);
        if (claimed == null || isConnectionClass(failure)) {
            return Step.ABORTED;
        }
        try {
            Instant now = clock.instant();
            if (ApplyRetry.afterFailure(claimed.applyAttempts(), now) instanceof ApplyRetry.RetryAt retry) {
                transactions.executeWithoutResult(status -> inbox.recordFailedAttempt(claimed.id(), retry.at()));
            } else {
                giveUp(claimed, now);
            }
            return Step.HANDLED;
        } catch (RuntimeException recording) {
            telemetry.applyFailed(claimed.id(), recording);
            return Step.ABORTED;
        }
    }

    private void giveUp(Claimed claimed, Instant now) {
        Integer marked = transactions.execute(
                status -> inbox.markDecided(claimed.id(), "refused", LedgerTelemetry.EXHAUSTED, null, now));
        if (marked != null && marked == 1) {
            telemetry.exhausted(claimed.id(), claimed.provider(), inbox.readEvent(claimed.eventJson()));
        }
    }

    private static boolean isConnectionClass(Throwable failure) {
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++, cause = cause.getCause()) {
            if (cause instanceof DataAccessResourceFailureException
                    || cause instanceof CannotGetJdbcConnectionException
                    || cause instanceof TransientDataAccessResourceException
                    || cause instanceof CannotCreateTransactionException) {
                return true;
            }
        }
        return false;
    }
}
