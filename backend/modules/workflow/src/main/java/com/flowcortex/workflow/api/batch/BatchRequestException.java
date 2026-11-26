package com.flowcortex.workflow.api.batch;

import com.flowcortex.shared.web.LocalizedMessage;

/** A batch request value that is not acceptable; rendered as a 400 problem naming {@link #field()}. */
public class BatchRequestException extends RuntimeException {

    private final String field;
    private final LocalizedMessage detail;

    public BatchRequestException(String field, LocalizedMessage detail) {
        super(field + ": " + detail);
        this.field = field;
        this.detail = detail;
    }

    /** The request field or parameter that was refused. */
    public String field() {
        return field;
    }

    /** The bundle key of the sentence shown for the field. */
    public LocalizedMessage detail() {
        return detail;
    }
}
