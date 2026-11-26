package com.flowcortex.billing.refund;

/** The payment has no pending request to cancel: 409 with code {@value #CODE} ({@link RefundProblemHandler}). */
public class RefundRequestNotCancellableException extends RuntimeException {

    static final String CODE = "refund_request_not_cancellable";

    RefundRequestNotCancellableException() {
        super(CODE);
    }
}
