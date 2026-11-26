package com.flowcortex.billing.reconcile;

import com.flowcortex.billing.ledger.MoneyStatus;
import java.util.Optional;

/**
 * What a provider's answer says about the money, as the classifier reads it: the status derived from it, or
 * the reason the derivation was refused together with the status its code alone implies, and the
 * provider's own transaction id (null when it named none).
 */
record ProviderAccount(
        Optional<MoneyStatus> derived,
        Optional<String> refusal,
        Optional<MoneyStatus> codeMoney,
        String providerTransactionId) {
}
