package com.flowcortex.billing.provider;

/**
 * Asks the provider what became of an order. The request reference identifies this query itself and is new
 * for every query, so each answer has its own evidence reference.
 */
public record QueryTransaction(String orderRef, String requestRef) {
}
