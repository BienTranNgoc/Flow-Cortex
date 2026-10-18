package com.flowcortex.credit.api;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Members' credit balances and what workflow run steps cost. With {@code flowcortex.credits.enabled}
 * off, {@link #debitForStep} allows every step without charging and {@link #refundStep} does nothing;
 * callers that show prices check {@link #enabled()} first.
 */
public interface CreditAccounts {

    boolean enabled();

    /** The configured cost of each step type, summed; throws {@link IllegalStateException} for an unpriced type. */
    long estimate(List<String> stepTypes);

    /** Throws {@link IllegalStateException} naming every type that has no configured cost. */
    void requirePriced(Collection<String> stepTypes);

    /** The member's balance; {@code 0} when they have no wallet yet. */
    long balance(UUID userId);

    /**
     * Charges the step's cost in the caller's transaction, only if the balance covers it. Returns
     * {@code false} when it does not (nothing is written); a step that costs nothing is never recorded.
     */
    boolean debitForStep(UUID userId, UUID runId, UUID runStepId, String stepType);

    /**
     * Returns the step's charge to its member in the caller's transaction, unless {@code errorCode} is
     * one the member caused. At most once per step, however often it is called.
     */
    void refundStep(UUID runId, UUID runStepId, String errorCode);

    /** What the run has cost: its charges minus its refunds. */
    long chargedForRun(UUID runId);
}
