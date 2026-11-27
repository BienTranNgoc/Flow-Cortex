package com.flowcortex.workflow.application;

import com.flowcortex.shared.web.LocalizedMessage;

/** A feedback request value the rules refuse; rendered as a 400 problem naming {@link #field()}. */
public class RunFeedbackRequestException extends RuntimeException {

    private final String field;
    private final LocalizedMessage detail;

    RunFeedbackRequestException(String field, LocalizedMessage detail) {
        super(field + ": " + detail);
        this.field = field;
        this.detail = detail;
    }

    /** The request field that was refused: {@code verdict}, {@code issue} or {@code detail}. */
    public String field() {
        return field;
    }

    /** The bundle key of the sentence shown for the field. */
    public LocalizedMessage detail() {
        return detail;
    }
}
