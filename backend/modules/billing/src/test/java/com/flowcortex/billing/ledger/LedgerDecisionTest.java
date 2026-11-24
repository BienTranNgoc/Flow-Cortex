package com.flowcortex.billing.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.billing.ledger.LedgerDecision.Applied;
import com.flowcortex.billing.ledger.LedgerDecision.Decision;
import com.flowcortex.billing.ledger.LedgerDecision.ProviderFacts;
import com.flowcortex.billing.ledger.LedgerDecision.Refused;
import com.flowcortex.billing.ledger.LedgerDecision.Skipped;
import com.flowcortex.billing.ledger.LedgerDecision.TransactionSnapshot;
import com.flowcortex.billing.payment.PaymentProviderEvent;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LedgerDecisionTest {

    private static final String ORDER_REF = "fc_0123456789abcdef0123456789abcdef";
    private static final UUID PAYMENT_ID = UUID.fromString("5b0f4f0e-7a43-4e55-9c1b-0d7f6d1f2a10");
    private static final Instant T_0 = Instant.parse("2026-11-20T10:00:00Z");
    private static final Instant T_1 = Instant.parse("2026-11-20T10:00:01Z");
    private static final Instant T_5 = Instant.parse("2026-11-20T10:00:05Z");
    private static final Instant T_9 = Instant.parse("2026-11-20T10:00:09Z");

    private static PaymentProviderEvent event(String provider, String code, Long amount, Instant at) {
        return new PaymentProviderEvent(
                "1",
                provider,
                PaymentProviderEvent.Kind.INBOUND_PAYMENT_CALLBACK,
                new PaymentProviderEvent.Reference(ORDER_REF, "0f8fad5b-d9cb-469f-a165-70867728950e", "4088878653"),
                code,
                "Successful.",
                amount,
                at,
                "qr",
                T_9);
    }

    private static PaymentProviderEvent momo(String code, Long amount, Instant at) {
        return event("momo", code, amount, at);
    }

    private static Optional<TransactionSnapshot> snapshot(MoneyStatus status, Instant providerStatusAt) {
        return Optional.of(new TransactionSnapshot(PAYMENT_ID, status, 50_000L, providerStatusAt));
    }

    @Test
    void noEventIsUnreadableWithoutASnapshot() {
        assertThat(LedgerDecision.decide(Optional.empty(), Optional.empty()))
                .isEqualTo(new Refused("event_unreadable", false));
    }

    @Test
    void noEventIsUnreadableWhateverTheSnapshot() {
        assertThat(LedgerDecision.decide(Optional.empty(), snapshot(MoneyStatus.PENDING, null)))
                .isEqualTo(new Refused("event_unreadable", false));
    }

    @Test
    void aSuccessEventForAMissingPaymentIsUnappliedMoney() {
        assertThat(LedgerDecision.decide(Optional.of(momo("0", 50_000L, T_5)), Optional.empty()))
                .isEqualTo(new Refused("transaction_not_found", true));
    }

    @Test
    void aDeclineEventForAMissingPaymentIsNotUnappliedMoney() {
        assertThat(LedgerDecision.decide(Optional.of(momo("1006", null, T_5)), Optional.empty()))
                .isEqualTo(new Refused("transaction_not_found", false));
    }

    @Test
    void aMissingPaymentOfAnUnreadableProviderIsNotUnappliedMoney() {
        assertThat(LedgerDecision.decide(Optional.of(event("onepay", "0", 50_000L, T_5)), Optional.empty()))
                .isEqualTo(new Refused("transaction_not_found", false));
    }

    @Test
    void aSuccessForAPendingPaymentIsAppliedWithTheProvidersFacts() {
        Decision decision = LedgerDecision.decide(Optional.of(momo("0", 50_000L, T_5)), snapshot(MoneyStatus.PENDING, null));

        assertThat(decision).isEqualTo(new Applied(
                MoneyStatus.PENDING,
                MoneyStatus.SUCCEEDED,
                new ProviderFacts("0", "Successful.", "4088878653", "qr", T_5, "none")));
    }

    @Test
    void aLateSuccessAfterExpiryIsApplied() {
        Decision decision = LedgerDecision.decide(Optional.of(momo("0", 50_000L, T_9)), snapshot(MoneyStatus.EXPIRED, T_5));

        assertThat(decision).isInstanceOfSatisfying(Applied.class, applied -> {
            assertThat(applied.from()).isEqualTo(MoneyStatus.EXPIRED);
            assertThat(applied.to()).isEqualTo(MoneyStatus.SUCCEEDED);
        });
    }

    @Test
    void aDeclineIsAppliedWithTheOwnerOfTheProblem() {
        Decision decision = LedgerDecision.decide(Optional.of(momo("1006", null, T_5)), snapshot(MoneyStatus.PENDING, null));

        assertThat(decision).isInstanceOfSatisfying(Applied.class, applied -> {
            assertThat(applied.to()).isEqualTo(MoneyStatus.FAILED);
            assertThat(applied.facts().owner()).isEqualTo("customer");
            assertThat(applied.facts().resultCode()).isEqualTo("1006");
        });
    }

    @Test
    void aRedeliveredSuccessWithALaterTimestampIsSkipped() {
        assertThat(LedgerDecision.decide(Optional.of(momo("0", 50_000L, T_9)), snapshot(MoneyStatus.SUCCEEDED, T_5)))
                .isEqualTo(new Skipped("already_in_state"));
    }

    @Test
    void aDeclineForAFailedPaymentIsSkipped() {
        assertThat(LedgerDecision.decide(Optional.of(momo("1006", null, T_5)), snapshot(MoneyStatus.FAILED, T_1)))
                .isEqualTo(new Skipped("already_in_state"));
    }

    @Test
    void aStillPendingCodeForAPendingPaymentIsSkipped() {
        assertThat(LedgerDecision.decide(Optional.of(momo("7000", null, T_5)), snapshot(MoneyStatus.PENDING, null)))
                .isEqualTo(new Skipped("already_in_state"));
    }

    @Test
    void aCodeWithoutMoneyMeaningIsSkipped() {
        assertThat(LedgerDecision.decide(Optional.of(momo("99", null, T_5)), snapshot(MoneyStatus.PENDING, null)))
                .isEqualTo(new Skipped("no_money_axis"));
    }

    @Test
    void aCodeWithoutMoneyMeaningIsSkippedBeforeAnyOrderingCheck() {
        assertThat(LedgerDecision.decide(Optional.of(momo("99", null, T_1)), snapshot(MoneyStatus.PENDING, T_5)))
                .isEqualTo(new Skipped("no_money_axis"));
    }

    @Test
    void aStaleDeclineIsRefused() {
        assertThat(LedgerDecision.decide(Optional.of(momo("1006", null, T_1)), snapshot(MoneyStatus.PENDING, T_5)))
                .isEqualTo(new Refused("stale_event", false));
    }

    @Test
    void aSuccessWithoutATimestampAfterOneIsRecordedIsRefusedAsUnorderedUnappliedMoney() {
        assertThat(LedgerDecision.decide(Optional.of(momo("0", 50_000L, null)), snapshot(MoneyStatus.PENDING, T_5)))
                .isEqualTo(new Refused("unordered_event", true));
    }

    @Test
    void aSuccessForAFailedPaymentIsRefusedAsUnappliedMoney() {
        assertThat(LedgerDecision.decide(Optional.of(momo("0", 50_000L, T_9)), snapshot(MoneyStatus.FAILED, T_5)))
                .isEqualTo(new Refused("undeclared_transition", true));
    }

    @Test
    void aStillPendingCodeForASucceededPaymentIsRefusedAsBackward() {
        assertThat(LedgerDecision.decide(Optional.of(momo("7000", null, T_9)), snapshot(MoneyStatus.SUCCEEDED, T_5)))
                .isEqualTo(new Refused("backward_transition", false));
    }

    @Test
    void aSuccessForAnotherAmountIsRefusedAsUnappliedMoney() {
        assertThat(LedgerDecision.decide(Optional.of(momo("0", 5_000L, T_5)), snapshot(MoneyStatus.PENDING, null)))
                .isEqualTo(new Refused("amount_mismatch", true));
    }

    @Test
    void aSuccessWithNoAmountIsRefusedAsUnappliedMoney() {
        assertThat(LedgerDecision.decide(Optional.of(momo("0", null, T_5)), snapshot(MoneyStatus.PENDING, null)))
                .isEqualTo(new Refused("amount_unstated", true));
    }

    @ParameterizedTest
    @CsvSource({
        "8888, unmapped_code",
        "99999, unmapped_code"
    })
    void aCodeNobodyMappedIsRefusedWithoutBeingMoney(String code, String reason) {
        assertThat(LedgerDecision.decide(Optional.of(momo(code, null, T_5)), snapshot(MoneyStatus.PENDING, null)))
                .isEqualTo(new Refused(reason, false));
    }

    @Test
    void anEventWithNoCodeIsRefused() {
        assertThat(LedgerDecision.decide(Optional.of(momo(null, null, T_5)), snapshot(MoneyStatus.PENDING, null)))
                .isEqualTo(new Refused("no_result_code", false));
    }

    @Test
    void anEventOfAProviderWithoutATableIsRefused() {
        assertThat(LedgerDecision.decide(Optional.of(event("onepay", "0", 50_000L, T_5)), snapshot(MoneyStatus.PENDING, null)))
                .isEqualTo(new Refused("unknown_provider", false));
    }
}
