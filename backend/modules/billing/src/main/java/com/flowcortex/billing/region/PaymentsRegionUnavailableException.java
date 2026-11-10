package com.flowcortex.billing.region;

import com.flowcortex.shared.web.LocalizedMessage;

/** A gated request from a caller registered outside Vietnam: 403 with code {@value #CODE} ({@link PaymentsRegionProblemHandler}). */
public class PaymentsRegionUnavailableException extends RuntimeException {

    public static final String CODE = "payments_region_unavailable";

    private static final LocalizedMessage DETAIL = new LocalizedMessage("billing.paymentsRegionUnavailable");

    public PaymentsRegionUnavailableException() {
        super(DETAIL.toString());
    }

    /** The bundle key for the problem detail. */
    public LocalizedMessage getDetail() {
        return DETAIL;
    }
}
