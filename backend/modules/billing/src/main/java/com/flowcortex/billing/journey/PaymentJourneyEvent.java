package com.flowcortex.billing.journey;

/**
 * An observation a payment page reports about what its payer saw (the bodies of
 * {@code contracts/payment-journey-event.schema.json}). It is never the truth about a payment, and no
 * field can hold a caller-chosen string beyond a minted reference or an identifier checked by shape.
 */
public sealed interface PaymentJourneyEvent {

    /** A payment was opened and the payer was handed to the provider's page: one of a plan or a package, never both. */
    record PaymentOpened(String orderRef, String transactionId, boolean replayed, String planId, String packageId)
            implements PaymentJourneyEvent {
    }

    /** The payer came back; {@code orderRef} is set only when {@code origin} is {@link ReturnRefOrigin#OURS}. */
    record PayerReturned(PayerReturnOutcome outcome, ReturnRefOrigin origin, String orderRef)
            implements PaymentJourneyEvent {
    }
}
