package com.flowcortex.billing.reconcile;

import com.flowcortex.billing.ledger.LedgerDecision.Applied;
import com.flowcortex.billing.ledger.LedgerDecision.Decision;
import com.flowcortex.billing.ledger.LedgerDecision.Refused;
import com.flowcortex.billing.ledger.MoneyStatus;
import java.util.Optional;
import java.util.Set;

/** What a provider's answer meant for the payment's reconciliation row, decided from values alone. */
final class ReconcileVerdict {

    /** What happens to the row after an answer. */
    enum Disposition { ESCALATE, RESOLVE, ADVANCE }

    /** Refusals that mean a newer account was already applied: the rules working, not a disagreement. */
    private static final Set<String> SUPERSEDING = Set.of("stale_event", "unordered_event", "backward_transition");

    private ReconcileVerdict() {
    }

    /**
     * The outcome the row keeps: {@code discrepancy}, {@code resolved}, {@code still_processing},
     * {@code superseded} or {@code unchanged}.
     */
    static String outcome(Decision decision, Optional<DriftClass> driftClass, Optional<MoneyStatus> derived) {
        if (driftClass.isPresent()) {
            return "discrepancy";
        }
        if (decision instanceof Applied) {
            return "resolved";
        }
        if (derived.filter(MoneyStatus.PENDING::equals).isPresent()) {
            return "still_processing";
        }
        if (decision instanceof Refused refused && SUPERSEDING.contains(refused.reason())) {
            return "superseded";
        }
        return "unchanged";
    }

    /** What became of the correction: {@code corrected}, {@code superseded} or {@code refused}. */
    static String resolution(Decision decision) {
        if (decision instanceof Applied) {
            return "corrected";
        }
        if (decision instanceof Refused refused && SUPERSEDING.contains(refused.reason())) {
            return "superseded";
        }
        return "refused";
    }

    static Disposition disposition(boolean needsPerson, MoneyStatus statusAfter) {
        if (needsPerson) {
            return Disposition.ESCALATE;
        }
        return statusAfter == MoneyStatus.PENDING ? Disposition.ADVANCE : Disposition.RESOLVE;
    }
}
