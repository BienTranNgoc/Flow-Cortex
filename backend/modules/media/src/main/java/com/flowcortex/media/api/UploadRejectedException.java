package com.flowcortex.media.api;

/** An upload request or an uploaded file the media engine refuses; the message is written for the member. */
public class UploadRejectedException extends RuntimeException {

    private final String field;

    public UploadRejectedException(String field, String message) {
        super(message);
        this.field = field;
    }

    /** The request field the refusal belongs to, for a field-scoped validation error. */
    public String field() {
        return field;
    }
}
