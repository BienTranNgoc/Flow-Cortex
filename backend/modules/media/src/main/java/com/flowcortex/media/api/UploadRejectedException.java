package com.flowcortex.media.api;

import com.flowcortex.shared.web.LocalizedMessage;

/** An upload request or an uploaded file the media engine refuses; the detail is written for the member. */
public class UploadRejectedException extends RuntimeException {

    private final String field;
    private final LocalizedMessage detail;

    public UploadRejectedException(String field, LocalizedMessage detail) {
        super(detail.toString());
        this.field = field;
        this.detail = detail;
    }

    /** The request field the refusal belongs to, for a field-scoped validation error. */
    public String field() {
        return field;
    }

    /** The bundle key for the member-facing sentence. */
    public LocalizedMessage detail() {
        return detail;
    }
}
