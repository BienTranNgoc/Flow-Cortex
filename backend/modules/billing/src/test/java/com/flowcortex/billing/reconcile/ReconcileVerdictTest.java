package com.flowcortex.billing.reconcile;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.billing.ledger.LedgerDecision.Applied;
import com.flowcortex.billing.ledger.LedgerDecision.Decision;
import com.flowcortex.billing.ledger.LedgerDecision.ProviderFacts;
import com.flowcortex.billing.ledger.LedgerDecision.Refused;
import com.flowcortex.billing.ledger.LedgerDecision.Skipped;
import com.flowcortex.billing.ledger.MoneyStatus;
import com.flowcortex.billing.reconcile.ReconcileVerdict.Disposition;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ReconcileVerdictTest {

    private static final Decision APPLIED = new Applied(MoneyStatus.PENDING, MoneyStatus.SUCCEEDED,
            new ProviderFacts("0", null, "4088878653", null, null, "none"));
    private static final Optional<DriftClass> NO_CLASS = Optional.empty();

    private static Decision refused(String reason) {
        return new Refused(reason, false);
    }

    @Test
    void anAppliedAnswerIsResolvedUnlessItWasAlsoADisagreement() {
        assertThat(ReconcileVerdict.outcome(APPLIED, NO_CLASS, Optional.of(MoneyStatus.SUCCEEDED)))
                .isEqualTo("resolved");
        assertThat(ReconcileVerdict.outcome(APPLIED, Optional.of(DriftClass.MISSED_CONFIRMATION),
                Optional.of(MoneyStatus.SUCCEEDED))).isEqualTo("discrepancy");
    }

    @Test
    void anAnswerThatSaysTheOrderIsStillInFlightIsStillProcessing() {
        assertThat(ReconcileVerdict.outcome(new Skipped("already_in_state"), NO_CLASS,
                Optional.of(MoneyStatus.PENDING))).isEqualTo("still_processing");
    }

    @Test
    void anAnswerWithoutAMoneyMeaningChangesNothing() {
        assertThat(ReconcileVerdict.outcome(new Skipped("no_money_axis"), NO_CLASS, Optional.empty()))
                .isEqualTo("unchanged");
    }

    @Test
    void anOlderAnswerLosesToWhatIsRecorded() {
        for (String reason : new String[] {"stale_event", "unordered_event", "backward_transition"}) {
            assertThat(ReconcileVerdict.outcome(refused(reason), NO_CLASS, Optional.of(MoneyStatus.SUCCEEDED)))
                    .isEqualTo("superseded");
        }
    }

    @Test
    void aRefusedAnswerThatIsADisagreementIsADiscrepancy() {
        assertThat(ReconcileVerdict.outcome(refused("stale_event"), Optional.of(DriftClass.MISSED_CONFIRMATION),
                Optional.of(MoneyStatus.SUCCEEDED))).isEqualTo("discrepancy");
        assertThat(ReconcileVerdict.outcome(refused("undeclared_transition"),
                Optional.of(DriftClass.MISSED_CONFIRMATION), Optional.of(MoneyStatus.SUCCEEDED)))
                .isEqualTo("discrepancy");
    }

    @Test
    void theResolutionNamesWhatBecameOfTheCorrection() {
        assertThat(ReconcileVerdict.resolution(APPLIED)).isEqualTo("corrected");
        assertThat(ReconcileVerdict.resolution(refused("stale_event"))).isEqualTo("superseded");
        assertThat(ReconcileVerdict.resolution(refused("undeclared_transition"))).isEqualTo("refused");
    }

    @Test
    void aRowNeedingAPersonIsEscalatedWhateverTheStatus() {
        assertThat(ReconcileVerdict.disposition(true, MoneyStatus.PENDING)).isEqualTo(Disposition.ESCALATE);
        assertThat(ReconcileVerdict.disposition(true, MoneyStatus.FAILED)).isEqualTo(Disposition.ESCALATE);
    }

    @Test
    void aPaymentNoLongerPendingLeavesTheLadder() {
        assertThat(ReconcileVerdict.disposition(false, MoneyStatus.SUCCEEDED)).isEqualTo(Disposition.RESOLVE);
        assertThat(ReconcileVerdict.disposition(false, MoneyStatus.EXPIRED)).isEqualTo(Disposition.RESOLVE);
        assertThat(ReconcileVerdict.disposition(false, MoneyStatus.FAILED)).isEqualTo(Disposition.RESOLVE);
    }

    @Test
    void aPaymentStillPendingClimbsARung() {
        assertThat(ReconcileVerdict.disposition(false, MoneyStatus.PENDING)).isEqualTo(Disposition.ADVANCE);
    }
}
