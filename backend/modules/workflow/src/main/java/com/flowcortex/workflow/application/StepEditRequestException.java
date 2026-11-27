package com.flowcortex.workflow.application;

import com.flowcortex.shared.web.LocalizedMessage;

/** A step-edit request value the rules refuse; rendered as a 400 problem naming {@link #field()}. */
public class StepEditRequestException extends RuntimeException {

    private final String field;
    private final LocalizedMessage detail;

    StepEditRequestException(String field, LocalizedMessage detail) {
        super(field + ": " + detail);
        this.field = field;
        this.detail = detail;
    }

    /** The request path that was refused: {@code fields}, {@code fields[1].text}, {@code expectedRevision}. */
    public String field() {
        return field;
    }

    /** The bundle key of the sentence shown for the field. */
    public LocalizedMessage detail() {
        return detail;
    }
}
