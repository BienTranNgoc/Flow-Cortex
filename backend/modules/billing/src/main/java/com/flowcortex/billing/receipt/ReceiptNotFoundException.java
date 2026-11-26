package com.flowcortex.billing.receipt;

import com.flowcortex.shared.web.LocalizedMessage;

/**
 * The payment is not the caller's, does not exist, or the identifier is malformed: 404 with code
 * {@value #CODE}, the same answer for all three ({@link ReceiptsProblemHandler}).
 */
class ReceiptNotFoundException extends RuntimeException {

    static final String CODE = "billing_receipt_not_found";

    private static final LocalizedMessage DETAIL = new LocalizedMessage("billing.receipt.notFound");

    ReceiptNotFoundException() {
        super(DETAIL.toString());
    }

    /** The bundle key for the problem detail. */
    LocalizedMessage getDetail() {
        return DETAIL;
    }
}
