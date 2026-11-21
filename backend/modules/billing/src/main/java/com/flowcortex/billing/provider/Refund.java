package com.flowcortex.billing.provider;

/** Returns {@code amountVnd} of a settled payment, identified by the provider's own transaction id. */
public record Refund(
        String orderRef,
        String requestRef,
        String providerTransactionId,
        long amountVnd,
        String description) {
}
