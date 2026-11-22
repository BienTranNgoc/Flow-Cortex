package com.flowcortex.billing.provider;

import com.flowcortex.billing.payment.PaymentProviderEvent;
import tools.jackson.databind.JsonNode;

/** The outcome of checking a provider callback's raw bytes: a signed, well-formed event or a reason. */
public sealed interface CallbackVerification {

    /** {@code requestId} is the provider's own id for the call, bounded and safe to store; null when absent. */
    record Verified(PaymentProviderEvent event, JsonNode payload, String requestId) implements CallbackVerification {
    }

    /** {@code reason} is a short code, never a value taken from the body. */
    record Rejected(String reason, String requestId) implements CallbackVerification {
    }
}
