package com.flowcortex.billing.tax;

import com.flowcortex.shared.web.LocalizedMessage;

/** The timeline's state refuses a publish; answered 409 with {@link #code()} ({@link VatRatesProblemHandler}). */
public class VatRateConflictException extends RuntimeException {

    static final String NO_OPEN_ROW = "vat_rate_no_open_row";
    static final String MULTIPLE_OPEN_ROWS = "vat_rate_multiple_open_rows";
    static final String NO_PRECEDENT = "vat_rate_no_precedent";
    static final String IN_USE = "vat_rate_in_use";

    private final String code;
    private final LocalizedMessage detail;

    VatRateConflictException(String code, LocalizedMessage detail) {
        super(code);
        this.code = code;
        this.detail = detail;
    }

    public String code() {
        return code;
    }

    /** The bundle key for the problem detail. */
    LocalizedMessage getDetail() {
        return detail;
    }
}
