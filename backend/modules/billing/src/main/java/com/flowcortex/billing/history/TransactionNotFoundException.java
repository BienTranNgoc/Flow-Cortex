package com.flowcortex.billing.history;

import com.flowcortex.shared.web.LocalizedMessage;

/**
 * The payment is not the caller's, does not exist, or the identifier is malformed: 404 with code
 * {@value #CODE}, the same answer for all three ({@link TransactionsProblemHandler}).
 */
class TransactionNotFoundException extends RuntimeException {

    static final String CODE = "transaction_not_found";

    private static final LocalizedMessage DETAIL = new LocalizedMessage("billing.transactionNotFound");

    TransactionNotFoundException() {
        super(DETAIL.toString());
    }

    /** The bundle key for the problem detail. */
    LocalizedMessage getDetail() {
        return DETAIL;
    }
}
