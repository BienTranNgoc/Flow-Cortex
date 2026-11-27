package com.flowcortex.billing.reconcile;

import com.flowcortex.billing.ledger.MoneyStatus;
import java.util.Optional;

/** Decides whether our record and a provider's answer disagree, and how. */
final class DriftClassifier {

    private DriftClassifier() {
    }

    /**
     * The class of the disagreement, or empty when the two accounts agree. Ours counts as unresolved here
     * when it is absent, {@code pending}, {@code failed} or {@code expired}: none of them stops the
     * provider from later saying the payer paid.
     */
    static Optional<DriftClass> classify(Optional<MoneyStatus> ours, ProviderAccount account) {
        if (account.refusal().isPresent()) {
            return classifyRefused(ours, account);
        }
        if (account.derived().isEmpty()) {
            return Optional.empty();
        }
        MoneyStatus theirs = account.derived().get();
        if (theirs == MoneyStatus.SUCCEEDED && unresolved(ours)) {
            return Optional.of(DriftClass.MISSED_CONFIRMATION);
        }
        if (theirs == MoneyStatus.FAILED && ours.filter(MoneyStatus.SUCCEEDED::equals).isPresent()) {
            return Optional.of(DriftClass.FALSE_POSITIVE);
        }
        return Optional.empty();
    }

    /**
     * Whether the disagreement needs a person: not one the correction closed, and not {@code never_arrived},
     * where the provider holds no record, so no money moved.
     */
    static boolean needsPerson(DriftClass driftClass, boolean correctionApplied) {
        return driftClass != DriftClass.NEVER_ARRIVED && !correctionApplied;
    }

    private static Optional<DriftClass> classifyRefused(Optional<MoneyStatus> ours, ProviderAccount account) {
        String reason = account.refusal().orElseThrow();
        if ("amount_mismatch".equals(reason)) {
            return Optional.of(DriftClass.AMOUNT_MISMATCH);
        }
        boolean unreadable = "no_result_code".equals(reason) || "unmapped_code".equals(reason);
        if (unreadable && account.providerTransactionId() == null && unresolved(ours)) {
            return Optional.of(DriftClass.NEVER_ARRIVED);
        }
        if (account.codeMoney().filter(MoneyStatus.SUCCEEDED::equals).isPresent()) {
            return Optional.of(DriftClass.MISSED_CONFIRMATION);
        }
        return Optional.empty();
    }

    private static boolean unresolved(Optional<MoneyStatus> ours) {
        return ours.isEmpty() || ours.get() == MoneyStatus.PENDING || ours.get() == MoneyStatus.FAILED
                || ours.get() == MoneyStatus.EXPIRED;
    }
}
