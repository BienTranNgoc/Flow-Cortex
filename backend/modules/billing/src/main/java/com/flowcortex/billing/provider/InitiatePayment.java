package com.flowcortex.billing.provider;

import java.util.Optional;

/** Opens a one-time payment. {@code requestRef} is the idempotency key the provider sees. */
public record InitiatePayment(
        String orderRef,
        String requestRef,
        long amountVnd,
        String description,
        Optional<PaymentInstrument> instrument) {
}
