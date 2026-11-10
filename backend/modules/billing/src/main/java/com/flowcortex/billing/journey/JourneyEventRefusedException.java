package com.flowcortex.billing.journey;

import com.flowcortex.shared.web.LocalizedMessage;

/** A journey body that is not one of the two observations: 400 with code {@value #CODE} ({@link JourneyProblemHandler}). */
class JourneyEventRefusedException extends RuntimeException {

    static final String CODE = "journey_event_invalid";

    private static final LocalizedMessage DETAIL = new LocalizedMessage("billing.journeyEventInvalid");

    JourneyEventRefusedException() {
        super(DETAIL.toString());
    }

    /** The bundle key for the problem detail. */
    LocalizedMessage getDetail() {
        return DETAIL;
    }
}
