package com.flowcortex.billing.provider;

import java.util.Set;

/**
 * One payment provider behind the port. A call that cannot be resolved raises
 * {@link ProviderTransportException}; one refused before sending raises {@link PaymentOperationRefusedException}.
 */
public interface PaymentProvider {

    /** The provider's key as it appears in events, e.g. {@code momo}. */
    String key();

    Set<PaymentInstrument> offeredInstruments();

    ProviderCallOutcome initiatePayment(InitiatePayment operation);

    ProviderCallOutcome queryTransaction(QueryTransaction operation);

    ProviderCallOutcome refund(Refund operation);

    /** Asks about a refund; the answer is reduced to the verdict a caller can act on. */
    RefundQueryOutcome queryRefund(QueryRefund operation);

    /** Checks a callback's raw bytes: signature, shape, bounds. Never throws for bad input. */
    CallbackVerification verifyCallback(byte[] rawBody);
}
