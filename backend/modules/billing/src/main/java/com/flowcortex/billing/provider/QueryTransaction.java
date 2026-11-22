package com.flowcortex.billing.provider;

/** Asks the provider what became of an order, reusing the request reference of the operation in doubt. */
public record QueryTransaction(String orderRef, String requestRef) {
}
