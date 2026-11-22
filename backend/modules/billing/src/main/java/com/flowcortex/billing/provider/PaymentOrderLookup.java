package com.flowcortex.billing.provider;

import java.util.Optional;

/**
 * Finds what was recorded when an operation was opened, so a provider callback can be checked against it.
 * Implemented by whichever component records operations before calling the provider.
 */
public interface PaymentOrderLookup {

    Optional<RecordedOrder> find(String provider, String orderRef);

    /** What the order was opened with: the amount in đồng and the provider's merchant code. */
    record RecordedOrder(long amountVnd, String merchantCode) {
    }
}
