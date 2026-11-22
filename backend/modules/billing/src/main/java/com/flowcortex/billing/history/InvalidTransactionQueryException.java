package com.flowcortex.billing.history;

import com.flowcortex.shared.web.LocalizedMessage;

/** A history query the service refuses: 400 with {@link #code()} ({@link TransactionsProblemHandler}). */
class InvalidTransactionQueryException extends RuntimeException {

    static final String PAGINATION = "invalid_pagination";
    static final String QUERY_FLAG = "invalid_query_flag";

    private final String code;
    private final LocalizedMessage detail;

    private InvalidTransactionQueryException(String code, LocalizedMessage detail) {
        super(detail.toString());
        this.code = code;
        this.detail = detail;
    }

    static InvalidTransactionQueryException pagination() {
        return new InvalidTransactionQueryException(PAGINATION, new LocalizedMessage("billing.invalidPagination"));
    }

    static InvalidTransactionQueryException flag() {
        return new InvalidTransactionQueryException(QUERY_FLAG, new LocalizedMessage("billing.invalidQueryFlag"));
    }

    String code() {
        return code;
    }

    /** The bundle key for the problem detail. */
    LocalizedMessage getDetail() {
        return detail;
    }
}
