package com.flowcortex.billing.provider;

/** Asks the provider whether a refund went through, reusing the request reference the refund was sent with. */
public record QueryRefund(String orderRef, String requestRef) {
}
