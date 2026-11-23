package com.flowcortex.billing.ledger;

import com.flowcortex.billing.provider.PaymentOrderLookup;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Answers the callback cross-check from the recorded payments. A database failure propagates to the caller. */
@Component
class LedgerOrderLookup implements PaymentOrderLookup {

    private final PaymentTransactionRepository payments;

    LedgerOrderLookup(PaymentTransactionRepository payments) {
        this.payments = payments;
    }

    @Override
    public Optional<RecordedOrder> find(String provider, String orderRef) {
        return payments.findRecordedOrder(provider, orderRef);
    }
}
