package com.flowcortex.billing.refund;

import com.flowcortex.shared.web.LocalizedMessage;

/**
 * The payment is not the caller's, does not exist, or the identifier is malformed: 404 with code {@value #CODE},
 * the same answer for all three as the payment history gives ({@link RefundProblemHandler}).
 */
public class RefundTransactionNotFoundException extends RuntimeException {

    static final String CODE = "transaction_not_found";

    private static final LocalizedMessage DETAIL = new LocalizedMessage("billing.transactionNotFound");

    RefundTransactionNotFoundException() {
        super(DETAIL.toString());
    }

    /** The bundle key for the problem detail. */
    LocalizedMessage getDetail() {
        return DETAIL;
    }
}
