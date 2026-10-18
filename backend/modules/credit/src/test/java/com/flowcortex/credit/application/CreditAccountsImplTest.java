package com.flowcortex.credit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.credit.domain.CreditLedgerEntry;
import com.flowcortex.credit.domain.CreditWallet;
import com.flowcortex.credit.infrastructure.CreditLedgerRepository;
import com.flowcortex.credit.infrastructure.CreditWalletRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CreditAccountsImplTest {

    private static final Instant NOW = Instant.parse("2026-10-18T09:00:00Z");
    private static final Map<String, Long> COSTS = Map.of("probe", 10L, "extract_audio", 20L, "echo", 0L);

    private final CreditWalletRepository wallets = mock(CreditWalletRepository.class);
    private final CreditLedgerRepository ledger = mock(CreditLedgerRepository.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final UUID userId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();
    private final UUID stepId = UUID.randomUUID();

    private CreditAccountsImpl accounts(boolean enabled) {
        return new CreditAccountsImpl(new CreditProperties(enabled, COSTS), wallets, ledger, clock);
    }

    @Test
    void reportsTheSwitchAndPricesFromTheProperties() {
        CreditAccountsImpl accounts = accounts(true);

        assertThat(accounts.enabled()).isTrue();
        assertThat(accounts(false).enabled()).isFalse();
        assertThat(accounts.estimate(List.of("probe", "extract_audio"))).isEqualTo(30);
        assertThatThrownBy(() -> accounts.requirePriced(List.of("probe", "render_video")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("render_video");
    }

    @Test
    void balanceIsTheWalletsOrZeroWithoutAWallet() {
        CreditWallet wallet = mock(CreditWallet.class);
        when(wallet.getBalance()).thenReturn(30L);
        when(wallets.findById(userId)).thenReturn(Optional.of(wallet));

        assertThat(accounts(true).balance(userId)).isEqualTo(30);
        assertThat(accounts(true).balance(UUID.randomUUID())).isZero();
    }

    @Test
    void aStepIsChargedOnlyWhenTheLedgerAcceptsTheDebit() {
        when(ledger.debit(any(), eq(userId), eq(runId), eq(stepId), eq(10L), eq(NOW))).thenReturn(1);

        assertThat(accounts(true).debitForStep(userId, runId, stepId, "probe")).isTrue();
    }

    @Test
    void aStepTheBalanceCannotCoverIsRefusedWithoutWriting() {
        when(ledger.debit(any(), eq(userId), eq(runId), eq(stepId), eq(20L), eq(NOW))).thenReturn(0);

        assertThat(accounts(true).debitForStep(userId, runId, stepId, "extract_audio")).isFalse();
    }

    @Test
    void aFreeStepAndADisabledSwitchAllowTheStepWithoutALedgerRow() {
        assertThat(accounts(true).debitForStep(userId, runId, stepId, "echo")).isTrue();
        assertThat(accounts(false).debitForStep(userId, runId, stepId, "probe")).isTrue();

        verify(ledger, never()).debit(any(), any(), any(), any(), anyLong(), any());
    }

    @Test
    void aSystemFailureAsksTheLedgerToRefundTheStepsDebitAndTheLedgerKeepsItToOnce() {
        givenDebit(20L);
        when(ledger.refund(any(), eq(stepId), eq(NOW))).thenReturn(1).thenReturn(0);

        accounts(true).refundStep(runId, stepId, "lease_expired");
        accounts(true).refundStep(runId, stepId, "lease_expired");

        verify(ledger, times(2)).refund(any(), eq(stepId), eq(NOW));
    }

    @Test
    void aFailureTheMemberCausedIsNotRefunded() {
        givenDebit(20L);

        accounts(true).refundStep(runId, stepId, "INSUFFICIENT_CREDITS");

        verify(ledger, never()).refund(any(), any(), any());
    }

    @Test
    void aStepThatWasNeverChargedAndADisabledSwitchRefundNothing() {
        when(ledger.findByRunStepIdAndKind(stepId, CreditLedgerEntry.DEBIT)).thenReturn(Optional.empty());

        accounts(true).refundStep(runId, stepId, "run_timeout");
        accounts(false).refundStep(runId, stepId, "run_timeout");

        verify(ledger, never()).refund(any(), any(), any());
    }

    @Test
    void whatARunCostComesFromTheLedger() {
        when(ledger.sumChargedForRun(runId)).thenReturn(11L);

        assertThat(accounts(true).chargedForRun(runId)).isEqualTo(11);
    }

    private void givenDebit(long amount) {
        CreditLedgerEntry debit = mock(CreditLedgerEntry.class);
        when(debit.getUserId()).thenReturn(userId);
        when(debit.getAmount()).thenReturn(amount);
        when(ledger.findByRunStepIdAndKind(stepId, CreditLedgerEntry.DEBIT)).thenReturn(Optional.of(debit));
    }
}
