package com.flowcortex.billing.terms;

import com.flowcortex.shared.web.LocalizedMessage;

/** The payment terms have neither a publication that applies nor a live copy to serve. */
public class TermsUnavailableException extends RuntimeException {

    private static final LocalizedMessage DETAIL = new LocalizedMessage("billing.agreementTermsUnavailable");

    public TermsUnavailableException() {
        super(DETAIL.toString());
    }

    /** The bundle key for the problem detail. */
    public LocalizedMessage getDetail() {
        return DETAIL;
    }
}
