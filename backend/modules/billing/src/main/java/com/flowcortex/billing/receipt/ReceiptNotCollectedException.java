package com.flowcortex.billing.receipt;

import com.flowcortex.shared.web.LocalizedMessage;

/**
 * The member's own payment has no paid time yet, so it has no receipt: 409 with code {@value #CODE}
 * ({@link ReceiptsProblemHandler}).
 */
public class ReceiptNotCollectedException extends RuntimeException {

    static final String CODE = "billing_receipt_not_collected";

    private static final LocalizedMessage DETAIL = new LocalizedMessage("billing.receipt.notCollected");

    public ReceiptNotCollectedException() {
        super(DETAIL.toString());
    }

    /** The bundle key for the problem detail. */
    LocalizedMessage getDetail() {
        return DETAIL;
    }
}
