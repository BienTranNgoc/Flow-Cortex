package com.flowcortex.billing.ledger;

import java.util.UUID;

/** What opening a payment returns: its id, our order reference, and the request reference sent to the provider. */
public record OpenedPayment(UUID transactionId, String orderRef, String requestRef) {
}
