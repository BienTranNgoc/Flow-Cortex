package com.flowcortex.billing.ledger;

import java.time.Instant;
import java.util.UUID;

/**
 * An effect of a payment reaching {@code succeeded}. The provider answer applier calls every implementation,
 * for a callback and a reconciliation alike, inside the transaction that applies the move, after the payment
 * row and its history are written, so an effect commits with the money record or not at all. An implementation
 * returns normally when it refuses to act, and throws to roll the whole move back.
 */
public interface SettledPlanPurchase {

    /**
     * @param transactionId the payment that moved to {@code succeeded}, of any kind
     * @param settledAt the paid time the move recorded
     */
    void onSucceeded(UUID transactionId, Instant settledAt);
}
