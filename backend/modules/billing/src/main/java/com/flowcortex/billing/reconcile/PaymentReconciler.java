package com.flowcortex.billing.reconcile;

import com.flowcortex.billing.ledger.ConnectionFailure;
import com.flowcortex.billing.ledger.LedgerDecision.Applied;
import com.flowcortex.billing.ledger.LedgerDecision.Decision;
import com.flowcortex.billing.ledger.LedgerDecision.TransactionSnapshot;
import com.flowcortex.billing.ledger.MoneyStatus;
import com.flowcortex.billing.ledger.ProviderAnswerApplier;
import com.flowcortex.billing.ledger.ProviderAnswerApplier.AnswerApplication;
import com.flowcortex.billing.ledger.ProviderAnswerApplier.MoneyReading;
import com.flowcortex.billing.payment.PaymentOperationRefs;
import com.flowcortex.billing.payment.PaymentProviderEvent;
import com.flowcortex.billing.provider.PaymentProvider;
import com.flowcortex.billing.provider.PaymentProviders;
import com.flowcortex.billing.provider.ProviderCircuitOpenException;
import com.flowcortex.billing.provider.QueryTransaction;
import com.flowcortex.billing.reconcile.ReconcileRepository.AskedPayment;
import com.flowcortex.billing.reconcile.ReconcileRepository.DriftRecord;
import com.flowcortex.billing.reconcile.ReconcileRepository.DueRow;
import com.flowcortex.billing.reconcile.ReconcileVerdict.Disposition;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Asks providers about payments whose outcome is unresolved or may be wrong and applies each answer through
 * the one applier a callback goes through. The ageing pass climbs a widening ladder over payments still
 * {@code pending}; the daily pass re-checks every payment once a day whatever its status.
 *
 * <p>An item is the provider call, which holds no database lock, then one transaction that holds the
 * payment's row lock: the answer is applied, a disagreement recorded, the schedule row disposed and the check
 * time set. A failed item keeps nothing and the pass goes on, except that an unreachable database ends it.
 * A pass stops taking items after {@link #PASS_BUDGET}, because the cluster runner runs a claimed job inline
 * and a long pass would hold up every other cluster job on the instance.
 */
@Component
@EnableConfigurationProperties(ReconcileProperties.class)
class PaymentReconciler {

    static final Duration PASS_BUDGET = Duration.ofSeconds(60);

    private static final String AGEING = "ageing";
    private static final String DAILY = "daily";

    private enum Step { HANDLED, CIRCUIT_OPEN, ABORTED }

    /** What an item decided, reported once its transaction has committed. */
    private record ItemResult(String outcome, Optional<DriftClass> driftClass, String resolution,
            boolean needsPerson, String evidenceRef, UUID paymentId) {
    }

    private final ReconcileRepository repository;
    private final ProviderAnswerApplier answers;
    private final PaymentProviders providers;
    private final ReconcileTelemetry telemetry;
    private final ReconcileProperties properties;
    private final Clock clock;
    private final TransactionTemplate transactions;

    PaymentReconciler(ReconcileRepository repository, ProviderAnswerApplier answers, PaymentProviders providers,
            ReconcileTelemetry telemetry, ReconcileProperties properties, Clock clock,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.answers = answers;
        this.providers = providers;
        this.telemetry = telemetry;
        this.properties = properties;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** One ageing pass: measure, enrol, then climb the due rows. Measures even when asking is switched off. */
    void ageingTick() {
        Instant started = clock.instant();
        measure(started);
        if (!properties.ageing().enabled()) {
            telemetry.pass(AGEING, "disabled", elapsedSince(started));
            return;
        }
        List<DueRow> due;
        try {
            repository.enrolDue(properties.floor(), started);
            due = repository.due(started, properties.ageing().batchSize());
        } catch (RuntimeException e) {
            telemetry.passFailed(AGEING, e);
            telemetry.pass(AGEING, "failed", elapsedSince(started));
            return;
        }
        String outcome = due.isEmpty() ? "idle" : "worked";
        for (DueRow row : due) {
            if (elapsedSince(started).compareTo(PASS_BUDGET) >= 0) {
                outcome = "budget_spent";
                break;
            }
            Step step = ageingItem(row);
            if (step != Step.HANDLED) {
                outcome = step == Step.CIRCUIT_OPEN ? "circuit_open" : "failed";
                break;
            }
        }
        telemetry.pass(AGEING, outcome, elapsedSince(started));
    }

    /** One daily pass over payments not checked for a day, whatever their status. */
    void dailyTick() {
        Instant started = clock.instant();
        if (!properties.daily().enabled()) {
            telemetry.pass(DAILY, "disabled", elapsedSince(started));
            return;
        }
        List<AskedPayment> candidates;
        try {
            candidates = repository.dailyCandidates(properties.floor(), started, providers.keys(),
                    properties.daily().batchSize());
        } catch (RuntimeException e) {
            telemetry.passFailed(DAILY, e);
            telemetry.pass(DAILY, "failed", elapsedSince(started));
            return;
        }
        String outcome = candidates.isEmpty() ? "idle" : "worked";
        for (AskedPayment payment : candidates) {
            if (elapsedSince(started).compareTo(PASS_BUDGET) >= 0) {
                outcome = "budget_spent";
                break;
            }
            Step step = dailyItem(payment);
            if (step != Step.HANDLED) {
                outcome = step == Step.CIRCUIT_OPEN ? "circuit_open" : "failed";
                break;
            }
        }
        telemetry.pass(DAILY, outcome, elapsedSince(started));
    }

    private void measure(Instant now) {
        try {
            telemetry.measured(repository.measure(properties.floor(), now));
        } catch (RuntimeException e) {
            telemetry.measureFailed(e);
        }
    }

    private Duration elapsedSince(Instant started) {
        return Duration.between(started, clock.instant());
    }

    private Step ageingItem(DueRow row) {
        try {
            return climb(row);
        } catch (RuntimeException e) {
            return failed(AGEING, row.transactionId(), e);
        }
    }

    private Step dailyItem(AskedPayment payment) {
        try {
            return recheck(payment);
        } catch (RuntimeException e) {
            return failed(DAILY, payment.id(), e);
        }
    }

    private Step failed(String pass, UUID paymentId, RuntimeException error) {
        telemetry.itemFailed(pass, paymentId, error);
        return ConnectionFailure.isConnectionClass(error) ? Step.ABORTED : Step.HANDLED;
    }

    /** Status first, then the provider's window, then the provider; only then is the provider asked. */
    private Step climb(DueRow row) {
        Instant now = clock.instant();
        AskedPayment payment = repository.findForAsking(row.transactionId())
                .orElseThrow(() -> new IllegalStateException("A schedule row has no payment"));
        if (payment.status() != MoneyStatus.PENDING) {
            repository.resolve(row.id(), "settled_elsewhere", false, now);
            telemetry.item(AGEING, "settled_elsewhere");
        } else if (ReconcileLadder.pastCeiling(payment.openedAt(), now)) {
            repository.escalate(row.id(), "ceiling", "escalated", false, now);
            telemetry.ceilingEscalation(payment.id());
            telemetry.item(AGEING, "escalated");
        } else {
            Optional<PaymentProvider> provider = providers.find(payment.provider());
            if (provider.isEmpty()) {
                repository.defer(row.id(), now.plus(properties.floor()), "provider_unavailable", now);
                telemetry.item(AGEING, "provider_unavailable");
            } else {
                return ask(provider.get(), payment, row);
            }
        }
        return Step.HANDLED;
    }

    private Step ask(PaymentProvider provider, AskedPayment payment, DueRow row) {
        String requestRef = PaymentOperationRefs.newRequestRef();
        Optional<PaymentProviderEvent> answer;
        try {
            answer = Optional.of(provider.queryTransaction(new QueryTransaction(payment.orderRef(), requestRef))
                    .event());
        } catch (ProviderCircuitOpenException circuitOpen) {
            Instant now = clock.instant();
            repository.defer(row.id(), circuitOpen.retryAt(), "circuit_open", now);
            telemetry.item(AGEING, "circuit_open");
            return Step.CIRCUIT_OPEN;
        } catch (RuntimeException callFailed) {
            Instant now = clock.instant();
            int asks = row.attempts() + 1;
            repository.advance(row.id(), asks, now, ReconcileLadder.nextAskAt(now, asks, properties.floor()),
                    "query_failed", now);
            repository.markChecked(payment.id(), now);
            telemetry.item(AGEING, "query_failed");
            return Step.HANDLED;
        }
        ItemResult result = transactions.execute(status -> settle(AGEING, row, payment, answer.get(), requestRef));
        report(AGEING, result);
        return Step.HANDLED;
    }

    private Step recheck(AskedPayment payment) {
        Optional<PaymentProvider> provider = providers.find(payment.provider());
        if (provider.isEmpty()) {
            return Step.HANDLED;
        }
        String requestRef = PaymentOperationRefs.newRequestRef();
        PaymentProviderEvent answer;
        try {
            answer = provider.get().queryTransaction(new QueryTransaction(payment.orderRef(), requestRef)).event();
        } catch (ProviderCircuitOpenException circuitOpen) {
            telemetry.item(DAILY, "circuit_open");
            return Step.CIRCUIT_OPEN;
        } catch (RuntimeException callFailed) {
            repository.markChecked(payment.id(), clock.instant());
            telemetry.item(DAILY, "query_failed");
            return Step.HANDLED;
        }
        ItemResult result = transactions.execute(status -> settle(DAILY, null, payment, answer, requestRef));
        report(DAILY, result);
        return Step.HANDLED;
    }

    /**
     * Runs inside the item's transaction. The daily pass skips a payment a schedule row has taken since it
     * was chosen, so one disagreement is never recorded under two evidence references.
     */
    private ItemResult settle(String pass, DueRow row, AskedPayment payment, PaymentProviderEvent answer,
            String requestRef) {
        Instant now = clock.instant();
        String evidenceRef = "query:" + requestRef;
        if (row == null) {
            repository.lockPayment(payment.id());
            if (repository.hasOpenRow(payment.id())) {
                return new ItemResult("skipped", Optional.empty(), null, false, evidenceRef, payment.id());
            }
        }
        AnswerApplication application = answers.apply(payment.provider(), Optional.of(answer), evidenceRef, now);
        Optional<MoneyStatus> ours = application.before().map(TransactionSnapshot::status);
        Decision decision = application.decision();
        MoneyReading reading = application.reading();
        Optional<DriftClass> driftClass = DriftClassifier.classify(ours, new ProviderAccount(reading.derived(),
                reading.derivationRefusal(), reading.codeMoney(), answer.reference().providerTransactionId()));
        boolean applied = decision instanceof Applied;
        boolean needsPerson = driftClass.map(found -> DriftClassifier.needsPerson(found, applied)).orElse(false);
        String outcome = ReconcileVerdict.outcome(decision, driftClass, reading.derived());
        String resolution = ReconcileVerdict.resolution(decision);
        if (driftClass.isPresent()) {
            repository.insertDrift(drift(pass, payment, answer, ours, reading, driftClass.get(), evidenceRef,
                    resolution, needsPerson, now));
        }
        MoneyStatus after = decision instanceof Applied move ? move.to() : ours.orElse(MoneyStatus.PENDING);
        Disposition disposition = ReconcileVerdict.disposition(needsPerson, after);
        if (row != null) {
            dispose(row, disposition, outcome, now);
        } else if (disposition == Disposition.ESCALATE) {
            repository.escalateForDaily(payment.id(), payment.openedAt(), now);
        }
        repository.markChecked(payment.id(), now);
        return new ItemResult(outcome, driftClass, resolution, needsPerson, evidenceRef, payment.id());
    }

    private void dispose(DueRow row, Disposition disposition, String outcome, Instant now) {
        if (disposition == Disposition.ESCALATE) {
            repository.escalate(row.id(), "discrepancy", outcome, true, now);
        } else if (disposition == Disposition.RESOLVE) {
            repository.resolve(row.id(), outcome, true, now);
        } else {
            int asks = row.attempts() + 1;
            repository.advance(row.id(), asks, now, ReconcileLadder.nextAskAt(now, asks, properties.floor()),
                    outcome, now);
        }
    }

    private DriftRecord drift(String pass, AskedPayment payment, PaymentProviderEvent answer,
            Optional<MoneyStatus> ours, MoneyReading reading, DriftClass driftClass, String evidenceRef,
            String resolution, boolean needsPerson, Instant now) {
        String theirs = reading.derived().map(MoneyStatus::wire)
                .orElseGet(() -> "refused:" + reading.derivationRefusal().orElse("unknown"));
        return new DriftRecord(payment.id(), driftClass, pass, payment.provider(), ours.map(MoneyStatus::wire).orElse(null),
                theirs, answer.resultCode(), payment.amountVnd(), answer.amount(), evidenceRef, resolution,
                needsPerson, now);
    }

    /** Reports a committed item. The drift line is logged here, not in the transaction, so a rollback logs none. */
    private void report(String pass, ItemResult result) {
        telemetry.item(pass, result.outcome());
        result.driftClass().ifPresent(found -> {
            telemetry.drift(pass, result.paymentId(), found, result.resolution(), result.evidenceRef(),
                    result.needsPerson());
            if (result.needsPerson()) {
                telemetry.discrepancyEscalation(pass);
            }
        });
    }
}
