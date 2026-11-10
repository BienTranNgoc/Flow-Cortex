package com.flowcortex.billing.provider;

import com.flowcortex.billing.payment.PaymentProviderEvent;
import tools.jackson.databind.JsonNode;

/** What a provider call produced: the normalised event and the provider's raw answer, uninterpreted. */
public record ProviderCallOutcome(PaymentProviderEvent event, JsonNode payload) {
}
