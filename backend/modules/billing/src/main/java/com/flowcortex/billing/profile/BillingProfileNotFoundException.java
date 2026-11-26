package com.flowcortex.billing.profile;

import com.flowcortex.shared.web.LocalizedMessage;

/**
 * The profile is not the caller's, does not exist, or the identifier is malformed: 404 with code
 * {@value #CODE}, the same answer for all three ({@link BillingProfilesProblemHandler}).
 */
public class BillingProfileNotFoundException extends RuntimeException {

    static final String CODE = "billing_profile_not_found";

    private static final LocalizedMessage DETAIL = new LocalizedMessage("billingProfile.notFound");

    public BillingProfileNotFoundException() {
        super(DETAIL.toString());
    }

    /** The bundle key for the problem detail. */
    LocalizedMessage getDetail() {
        return DETAIL;
    }
}
