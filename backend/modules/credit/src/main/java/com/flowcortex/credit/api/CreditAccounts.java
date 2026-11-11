package com.flowcortex.credit.api;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Members' credit balances and what workflow run steps cost. With {@code flowcortex.credits.enabled}
 * off, {@link #debitForStep} allows every step without charging and {@link #refundStep} does nothing;
 * callers that show prices check {@link #enabled()} first.
 *
 * <p>Every pricing method reads {@code rate_card} and {@code capability_pricing} at call time, prices at the
 * current instant whatever the switch says, and throws {@link IllegalStateException} naming the ref or step
 * type when nothing prices it. A {@code free} capability costs {@code 0} and writes no ledger row.
 */
public interface CreditAccounts {

    boolean enabled();

    /**
     * The price of each step type at its unit's default quantity, summed, read in one statement; throws for a
     * type with no mapping, an {@code mc} mapping or no active row.
     */
    long estimate(List<String> stepTypes);

    /**
     * Prices one rate-card ref at {@code quantity}. The ref must have exactly one key active now (one provider
     * and unit, no model, no dimensions); a ref with several, such as one priced by many providers, throws as
     * ambiguous and is priced through {@link #estimateLines} with the full key of each line.
     */
    long estimate(String rateCardRef, RateQuantity quantity);

    /** Sums the lines, each priced by its full key (ref, unit, provider, model, dimensions) and quantity. */
    long estimateLines(List<RateLine> lines);

    /**
     * Startup check: throws one {@link IllegalStateException} naming every capability that cannot be priced
     * from {@code capability_pricing} and {@code rate_card}, each with its reason.
     */
    void requirePriced(Collection<String> capabilities);

    /** The member's balance; {@code 0} when they have no wallet yet. */
    long balance(UUID userId);

    /**
     * Charges the step type's price, read in the caller's transaction, only if the balance covers it. Returns
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
