package com.flowcortex.billing.reconcile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.billing.ledger.LedgerDecision.Applied;
import com.flowcortex.billing.ledger.LedgerDecision.Decision;
import com.flowcortex.billing.ledger.LedgerDecision.ProviderFacts;
import com.flowcortex.billing.ledger.LedgerDecision.Refused;
import com.flowcortex.billing.ledger.LedgerDecision.Skipped;
import com.flowcortex.billing.ledger.LedgerDecision.TransactionSnapshot;
import com.flowcortex.billing.ledger.MoneyStatus;
import com.flowcortex.billing.ledger.ProviderAnswerApplier;
import com.flowcortex.billing.ledger.ProviderAnswerApplier.AnswerApplication;
import com.flowcortex.billing.ledger.ProviderAnswerApplier.MoneyReading;
import com.flowcortex.billing.payment.PaymentProviderEvent;
import com.flowcortex.billing.payment.PaymentProviderEvent.Kind;
import com.flowcortex.billing.payment.PaymentProviderEvent.Reference;
import com.flowcortex.billing.provider.CircuitState;
import com.flowcortex.billing.provider.PaymentProvider;
import com.flowcortex.billing.provider.PaymentProviders;
import com.flowcortex.billing.provider.ProviderCallOutcome;
import com.flowcortex.billing.provider.ProviderCircuitOpenException;
import com.flowcortex.billing.reconcile.ReconcileRepository.AskedPayment;
import com.flowcortex.billing.reconcile.ReconcileRepository.DriftRecord;
import com.flowcortex.billing.reconcile.ReconcileRepository.DueRow;
import com.flowcortex.billing.reconcile.ReconcileRepository.Measurement;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class PaymentReconcilerTest {

    private static final Instant NOW = Instant.parse("2026-11-24T10:00:00Z");
    private static final String ORDER_REF = "fc_0123456789abcdef0123456789abcdef";

    /** A clock that moves only when a test says so. */
    private static final class TestClock extends Clock {
        private Instant now = NOW;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final TestClock clock = new TestClock();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ReconcileRepository repository = mock(ReconcileRepository.class);
    private final ProviderAnswerApplier answers = mock(ProviderAnswerApplier.class);
    private final PaymentProvider momo = mock(PaymentProvider.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final ReconcileProperties properties = new ReconcileProperties(null, null, null);

    private PaymentReconciler reconciler(PaymentProviders providers) {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return new PaymentReconciler(repository, answers, providers, new ReconcileTelemetry(meters), properties,
                clock, transactionManager);
    }

    private PaymentReconciler withMomo() {
        when(momo.key()).thenReturn("momo");
        return reconciler(new PaymentProviders(List.of(momo)));
    }

    private static DueRow due(int attempts) {
        return new DueRow(UUID.randomUUID(), UUID.randomUUID(), attempts);
    }

    private static AskedPayment payment(UUID id, MoneyStatus status, Instant openedAt) {
        return new AskedPayment(id, "momo", ORDER_REF, status, openedAt, 50_000L);
    }

    private static PaymentProviderEvent answer(String code, Long amount) {
        return new PaymentProviderEvent("1", "momo", Kind.TRANSACTION_QUERY,
                new Reference(ORDER_REF, UUID.randomUUID().toString(), "4088878653"), code, "Message", amount, NOW,
                null, NOW);
    }

    private double items(String pass, String outcome) {
        return meters.counter("payment.reconcile.item", "pass", pass, "outcome", outcome).count();
    }

    private long passes(String pass, String outcome) {
        return meters.timer("payment.reconcile.pass", "pass", pass, "outcome", outcome).count();
    }

    @Test
    void anItemWhosePaymentCannotBeReadIsFailedAndTheNextRowIsHandled() {
        PaymentReconciler reconciler = withMomo();
        DueRow unreadable = due(0);
        DueRow settled = due(2);
        when(repository.due(NOW, 50)).thenReturn(List.of(unreadable, settled));
        when(repository.findForAsking(unreadable.transactionId())).thenThrow(new IllegalStateException("bad row"));
        when(repository.findForAsking(settled.transactionId())).thenReturn(Optional.of(
                payment(settled.transactionId(), MoneyStatus.SUCCEEDED, NOW.minus(Duration.ofDays(1)))));

        reconciler.ageingTick();

        verify(repository, never()).resolve(eq(unreadable.id()), anyString(), anyBoolean(), any());
        verify(repository).resolve(settled.id(), "settled_elsewhere", false, NOW);
        assertThat(items("ageing", "failed")).isEqualTo(1.0);
        assertThat(passes("ageing", "worked")).isEqualTo(1L);
    }

    @Test
    void anUnreachableDatabaseEndsThePass() {
        PaymentReconciler reconciler = withMomo();
        DueRow first = due(0);
        DueRow second = due(0);
        when(repository.due(NOW, 50)).thenReturn(List.of(first, second));
        when(repository.findForAsking(first.transactionId())).thenThrow(new RuntimeException("read",
                new CannotGetJdbcConnectionException("no connection", new SQLException("refused"))));

        reconciler.ageingTick();

        verify(repository, never()).findForAsking(second.transactionId());
        assertThat(passes("ageing", "failed")).isEqualTo(1L);
    }

    @Test
    void aPaymentSettledMeanwhileIsNotEscalatedForItsAge() {
        PaymentReconciler reconciler = withMomo();
        DueRow row = due(5);
        when(repository.due(NOW, 50)).thenReturn(List.of(row));
        when(repository.findForAsking(row.transactionId())).thenReturn(Optional.of(
                payment(row.transactionId(), MoneyStatus.SUCCEEDED, NOW.minus(Duration.ofDays(31)).minusSeconds(60))));

        reconciler.ageingTick();

        verify(repository).resolve(row.id(), "settled_elsewhere", false, NOW);
        verify(repository, never()).escalate(any(), anyString(), anyString(), anyBoolean(), any());
        verify(momo, never()).queryTransaction(any());
    }

    @Test
    void aPendingPaymentPastTheWindowIsEscalatedWithoutAskingTheProvider() {
        PaymentReconciler reconciler = withMomo();
        DueRow row = due(12);
        when(repository.due(NOW, 50)).thenReturn(List.of(row));
        when(repository.findForAsking(row.transactionId())).thenReturn(Optional.of(
                payment(row.transactionId(), MoneyStatus.PENDING, NOW.minus(Duration.ofDays(31)).minusSeconds(60))));

        reconciler.ageingTick();

        verify(repository).escalate(row.id(), "ceiling", "escalated", false, NOW);
        verify(momo, never()).queryTransaction(any());
        assertThat(meters.counter("payment.reconcile.escalation", "pass", "ageing", "reason", "ceiling").count())
                .isEqualTo(1.0);
    }

    @Test
    void aProviderAbsentFromTheDeploymentDefersTheRowByOneFloor() {
        PaymentReconciler reconciler = reconciler(new PaymentProviders(List.of()));
        DueRow row = due(2);
        when(repository.due(NOW, 50)).thenReturn(List.of(row));
        when(repository.findForAsking(row.transactionId())).thenReturn(Optional.of(
                payment(row.transactionId(), MoneyStatus.PENDING, NOW.minus(Duration.ofHours(1)))));

        reconciler.ageingTick();

        verify(repository).defer(row.id(), NOW.plus(Duration.ofMinutes(15)), "provider_unavailable", NOW);
        verify(repository, never()).advance(any(), anyInt(), any(), any(), anyString(), any());
    }

    @Test
    void anUnexpectedFailureOfTheProviderCallSpendsARungAndTheNextRowIsHandled() {
        PaymentReconciler reconciler = withMomo();
        DueRow failing = due(0);
        DueRow settled = due(1);
        when(repository.due(NOW, 50)).thenReturn(List.of(failing, settled));
        when(repository.findForAsking(failing.transactionId())).thenReturn(Optional.of(
                payment(failing.transactionId(), MoneyStatus.PENDING, NOW.minus(Duration.ofHours(1)))));
        when(repository.findForAsking(settled.transactionId())).thenReturn(Optional.of(
                payment(settled.transactionId(), MoneyStatus.FAILED, NOW.minus(Duration.ofHours(1)))));
        when(momo.queryTransaction(any())).thenThrow(new IllegalStateException("garbled answer"));

        reconciler.ageingTick();

        verify(repository).advance(failing.id(), 1, NOW, NOW.plus(Duration.ofMinutes(30)), "query_failed", NOW);
        verify(repository).resolve(settled.id(), "settled_elsewhere", false, NOW);
        assertThat(items("ageing", "query_failed")).isEqualTo(1.0);
    }

    @Test
    void anOpenCircuitDefersTheRowWithoutSpendingARungAndEndsThePass() {
        PaymentReconciler reconciler = withMomo();
        DueRow first = due(2);
        DueRow second = due(0);
        Instant retryAt = NOW.plusSeconds(60);
        when(repository.due(NOW, 50)).thenReturn(List.of(first, second));
        when(repository.findForAsking(first.transactionId())).thenReturn(Optional.of(
                payment(first.transactionId(), MoneyStatus.PENDING, NOW.minus(Duration.ofHours(1)))));
        when(momo.queryTransaction(any())).thenThrow(
                new ProviderCircuitOpenException("momo", "query", CircuitState.OPEN, retryAt));

        reconciler.ageingTick();

        verify(repository).defer(first.id(), retryAt, "circuit_open", NOW);
        verify(repository, never()).advance(any(), anyInt(), any(), any(), anyString(), any());
        verify(repository, never()).findForAsking(second.transactionId());
        assertThat(passes("ageing", "circuit_open")).isEqualTo(1L);
    }

    @Test
    void aPassThatHasRunItsBudgetTakesNoFurtherRow() {
        PaymentReconciler reconciler = withMomo();
        DueRow first = due(0);
        DueRow second = due(0);
        when(repository.due(NOW, 50)).thenReturn(List.of(first, second));
        when(repository.findForAsking(first.transactionId())).thenAnswer(call -> {
            clock.now = NOW.plus(PaymentReconciler.PASS_BUDGET);
            return Optional.of(payment(first.transactionId(), MoneyStatus.SUCCEEDED, NOW.minus(Duration.ofHours(1))));
        });

        reconciler.ageingTick();

        verify(repository, never()).findForAsking(second.transactionId());
        assertThat(passes("ageing", "budget_spent")).isEqualTo(1L);
    }

    @Test
    void aSwitchedOffPassAsksNothingButStillMeasures() {
        PaymentReconciler reconciler = new PaymentReconciler(repository, answers, new PaymentProviders(List.of()),
                new ReconcileTelemetry(meters), new ReconcileProperties(
                        new ReconcileProperties.Ageing(false, null, null), null, null),
                clock, transactionManager);
        when(repository.measure(Duration.ofMinutes(15), NOW)).thenReturn(new Measurement(2, 3000, 1));

        reconciler.ageingTick();

        verify(repository, never()).enrolDue(any(), any());
        verify(repository, never()).due(any(), anyInt());
        assertThat(meters.get("payment.reconcile.unresolved").gauge().value()).isEqualTo(2.0);
        assertThat(meters.get("payment.reconcile.unresolved.oldest_age_seconds").gauge().value()).isEqualTo(3000.0);
        assertThat(meters.get("payment.reconcile.escalated").gauge().value()).isEqualTo(1.0);
        assertThat(passes("ageing", "disabled")).isEqualTo(1L);
    }

    @Test
    void aFailedMeasurementDoesNotStopThePass() {
        PaymentReconciler reconciler = withMomo();
        when(repository.measure(any(), any())).thenThrow(new IllegalStateException("measure"));
        when(repository.due(NOW, 50)).thenReturn(List.of());

        reconciler.ageingTick();

        assertThat(passes("ageing", "idle")).isEqualTo(1L);
    }

    @Test
    void aLostCallbackRecoveredIsRecordedAsCorrectedAndTheRowIsResolved() {
        PaymentReconciler reconciler = withMomo();
        DueRow row = due(0);
        PaymentProviderEvent event = answer("0", 50_000L);
        when(repository.due(NOW, 50)).thenReturn(List.of(row));
        when(repository.findForAsking(row.transactionId())).thenReturn(Optional.of(
                payment(row.transactionId(), MoneyStatus.PENDING, NOW.minus(Duration.ofMinutes(20)))));
        when(momo.queryTransaction(any())).thenReturn(new ProviderCallOutcome(event, null));
        when(answers.apply(eq("momo"), eq(Optional.of(event)), anyString(), eq(NOW))).thenReturn(new AnswerApplication(
                Optional.of(new TransactionSnapshot(row.transactionId(), MoneyStatus.PENDING, 50_000L, null)),
                new Applied(MoneyStatus.PENDING, MoneyStatus.SUCCEEDED,
                        new ProviderFacts("0", "Message", "4088878653", null, NOW, "none")),
                new MoneyReading(Optional.of(MoneyStatus.SUCCEEDED), Optional.empty(),
                        Optional.of(MoneyStatus.SUCCEEDED))));

        reconciler.ageingTick();

        ArgumentCaptor<DriftRecord> drift = ArgumentCaptor.forClass(DriftRecord.class);
        verify(repository).insertDrift(drift.capture());
        assertThat(drift.getValue().driftClass()).isEqualTo(DriftClass.MISSED_CONFIRMATION);
        assertThat(drift.getValue().resolution()).isEqualTo("corrected");
        assertThat(drift.getValue().needsPerson()).isFalse();
        assertThat(drift.getValue().ourStatus()).isEqualTo("pending");
        assertThat(drift.getValue().providerStatus()).isEqualTo("succeeded");
        assertThat(drift.getValue().evidenceRef()).startsWith("query:");
        verify(repository).resolve(row.id(), "discrepancy", true, NOW);
        verify(repository).markChecked(row.transactionId(), NOW);
        verify(repository, never()).escalate(any(), anyString(), anyString(), anyBoolean(), any());
    }

    @Test
    void aDailyAnswerThatNeedsAPersonEscalatesTheRowAndAnAgreeingOneWritesNone() {
        PaymentReconciler reconciler = withMomo();
        AskedPayment failedBefore = payment(UUID.randomUUID(), MoneyStatus.FAILED, NOW.minus(Duration.ofDays(2)));
        AskedPayment agreeing = payment(UUID.randomUUID(), MoneyStatus.SUCCEEDED, NOW.minus(Duration.ofDays(2)));
        PaymentProviderEvent paid = answer("0", 50_000L);
        when(repository.dailyCandidates(Duration.ofMinutes(15), NOW, Set.of("momo"), 50))
                .thenReturn(List.of(failedBefore, agreeing));
        when(momo.queryTransaction(any())).thenReturn(new ProviderCallOutcome(paid, null));
        when(answers.apply(anyString(), any(), anyString(), any()))
                .thenReturn(application(failedBefore, MoneyStatus.FAILED, new Refused("undeclared_transition", true)),
                        application(agreeing, MoneyStatus.SUCCEEDED, new Skipped("already_in_state")));

        reconciler.dailyTick();

        verify(repository, times(1)).escalateForDaily(failedBefore.id(), failedBefore.openedAt(), NOW);
        verify(repository, never()).escalateForDaily(eq(agreeing.id()), any(), any());
        verify(repository, times(2)).markChecked(any(), eq(NOW));
        assertThat(meters.counter("payment.reconcile.drift", "class", "missed_confirmation", "resolution", "refused")
                .count()).isEqualTo(1.0);
    }

    /** A provider that says paid, applied against a payment recorded as {@code ours}. */
    private static AnswerApplication application(AskedPayment payment, MoneyStatus ours, Decision decision) {
        return new AnswerApplication(
                Optional.of(new TransactionSnapshot(payment.id(), ours, 50_000L, null)), decision,
                new MoneyReading(Optional.of(MoneyStatus.SUCCEEDED), Optional.empty(),
                        Optional.of(MoneyStatus.SUCCEEDED)));
    }
}
