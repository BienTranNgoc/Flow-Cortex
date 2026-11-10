package com.flowcortex.workflow.api.runhistory;

import com.flowcortex.shared.web.LocalizedMessage;

/** A run-history request value that is not acceptable; rendered as a 400 problem naming {@link #field()}. */
public class RunHistoryRequestException extends RuntimeException {

    private final String field;
    private final LocalizedMessage detail;

    public RunHistoryRequestException(String field, LocalizedMessage detail) {
        super(field + ": " + detail);
        this.field = field;
        this.detail = detail;
    }

    /** The request parameter or body field that was refused. */
    public String field() {
        return field;
    }

    /** The bundle key of the sentence shown for the field. */
    public LocalizedMessage detail() {
        return detail;
    }
}
