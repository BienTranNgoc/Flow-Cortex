package com.flowcortex.billing.ledger;

import com.flowcortex.billing.payment.PaymentOperationRefs;
import com.flowcortex.billing.terms.AcceptedTermsReader;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Opens payments: the money record is stored, with its first history entry, before any provider is called. */
@Service
public class PaymentLedger {

    private static final String OPENED = "opened";

    private final PaymentTransactionRepository payments;
    private final AcceptedTermsReader terms;
    private final Clock clock;

    PaymentLedger(PaymentTransactionRepository payments, AcceptedTermsReader terms, Clock clock) {
        this.payments = payments;
        this.terms = terms;
        this.clock = clock;
    }

    /**
     * Stores the payment at {@code pending} and its opening history entry in one transaction, pinning the
     * publication of the payment terms in force now. The minted order reference is not retried on a collision:
     * at 128 random bits a collision is a failure to surface.
     */
    @Transactional
    public OpenedPayment open(OpenPayment opening) {
        UUID id = UUID.randomUUID();
        String orderRef = PaymentOperationRefs.newOrderRef();
        String requestRef = PaymentOperationRefs.newRequestRef();
        Instant now = clock.instant();
        payments.insertOpened(id, opening, orderRef, requestRef, terms.currentPublicationId().orElse(null), now);
        payments.insertHistory(id, null, MoneyStatus.PENDING, OPENED, null, null, null, now);
        return new OpenedPayment(id, orderRef, requestRef);
    }
}
