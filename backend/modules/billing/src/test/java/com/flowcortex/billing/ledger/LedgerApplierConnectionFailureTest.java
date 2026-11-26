package com.flowcortex.billing.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flowcortex.billing.ledger.CallbackInbox.Claimed;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class LedgerApplierConnectionFailureTest {

    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final CallbackInbox inbox = mock(CallbackInbox.class);
    private final PaymentTransactionRepository payments = mock(PaymentTransactionRepository.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final LedgerApplier applier = new LedgerApplier(inbox,
            new ProviderAnswerApplier(payments, mock(ApplicationEventPublisher.class),
                    new DefaultListableBeanFactory().getBeanProvider(SettledPlanPurchase.class)),
            new LedgerTelemetry(meters), new LedgerApplyProperties(true, null, 100),
            Clock.fixed(NOW, ZoneOffset.UTC), transactionManager);

    @Test
    void anUnreachableDatabaseEndsThePassCountsOneFailureAndRecordsNoAttempt() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(inbox.claimNext(NOW)).thenThrow(new RuntimeException("claim failed",
                new CannotGetJdbcConnectionException("no connection", new SQLException("refused"))));

        applier.applyPending();

        verify(inbox, times(1)).claimNext(NOW);
        verify(inbox, never()).recordFailedAttempt(any(), any());
        verify(inbox, never()).markDecided(any(), any(), any(), any(), any());
        verifyNoInteractions(payments);
        assertThat(meters.get("payment.ledger.apply.failure").tag("error", "RuntimeException").counter().count())
                .isEqualTo(1.0);
        assertThat(meters.get("payment.ledger.apply.pass").tag("outcome", "failed").timer().count())
                .isEqualTo(1L);
    }

    @Test
    void aConnectionLossAfterTheClaimWalksTheCausesAndRecordsNoAttemptAgainstTheCallback() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(inbox.claimNext(NOW)).thenReturn(Optional.of(new Claimed(UUID.randomUUID(), "momo", "{}", 3)));
        when(inbox.readEvent("{}")).thenThrow(new RuntimeException("read failed", new IllegalStateException("outer",
                new CannotGetJdbcConnectionException("no connection", new SQLException("refused")))));

        applier.applyPending();

        verify(inbox, times(1)).claimNext(NOW);
        verify(inbox, never()).recordFailedAttempt(any(), any());
        verify(inbox, never()).markDecided(any(), any(), any(), any(), any());
        assertThat(meters.get("payment.ledger.apply.failure").tag("error", "RuntimeException").counter().count())
                .isEqualTo(1.0);
        assertThat(meters.get("payment.ledger.apply.pass").tag("outcome", "failed").timer().count())
                .isEqualTo(1L);
    }
}
