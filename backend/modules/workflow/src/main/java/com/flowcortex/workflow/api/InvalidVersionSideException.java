package com.flowcortex.workflow.api;

import com.flowcortex.shared.web.LocalizedMessage;

/** A comparison side that is neither {@code draft}, {@code published} nor a version number: a 400 naming the field. */
public class InvalidVersionSideException extends RuntimeException {

    private final String field;
    private final LocalizedMessage detail;

    public InvalidVersionSideException(String field, LocalizedMessage detail) {
        super(detail.toString());
        this.field = field;
        this.detail = detail;
    }

    /** The request parameter that held the bad value. */
    public String field() {
        return field;
    }

    /** The bundle key of the member-facing sentence. */
    public LocalizedMessage detail() {
        return detail;
    }
}
