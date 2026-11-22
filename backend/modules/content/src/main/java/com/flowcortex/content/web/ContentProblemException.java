package com.flowcortex.content.web;

import com.flowcortex.content.api.FieldError;
import com.flowcortex.shared.web.LocalizedMessage;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * A content refusal rendered as RFC 9457 problem {@code https://flow-cortex.dev/problems/<code>} by
 * {@link ContentProblemHandler}. The detail and every field error are message bundle keys, put into the
 * request's language by the handler.
 */
public class ContentProblemException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final LocalizedMessage detail;
    private final Map<String, Object> properties = new LinkedHashMap<>();
    private final Map<String, String> fieldErrors = new LinkedHashMap<>();

    public ContentProblemException(HttpStatus status, String code, LocalizedMessage detail) {
        super(detail.toString());
        this.status = status;
        this.code = code;
        this.detail = detail;
    }

    /** An unregistered, unknown or never-published document, or a snapshot that is not the document's. */
    public static ContentProblemException documentNotFound() {
        return new ContentProblemException(HttpStatus.NOT_FOUND, "document-not-found",
                new LocalizedMessage("content.document.notFound"));
    }

    /** The token the caller sent is not the stored draft's version. */
    public static ContentProblemException versionConflict(int currentVersion) {
        return new ContentProblemException(HttpStatus.CONFLICT, "version-conflict",
                new LocalizedMessage("content.document.versionConflict")).with("currentVersion", currentVersion);
    }

    public static ContentProblemException nothingToPublish() {
        return new ContentProblemException(HttpStatus.CONFLICT, "nothing-to-publish",
                new LocalizedMessage("content.document.nothingToPublish"));
    }

    /** A draft with blank required texts; {@code fields} lists every path. */
    public static ContentProblemException documentIncomplete(List<String> fields) {
        return new ContentProblemException(HttpStatus.UNPROCESSABLE_CONTENT, "document-incomplete",
                new LocalizedMessage("content.document.incomplete")).with("fields", List.copyOf(fields));
    }

    /** A save outside the bounds; {@code errors} lists the refused field paths with their message keys. */
    public static ContentProblemException documentInvalid(List<FieldError> errors) {
        ContentProblemException problem = new ContentProblemException(HttpStatus.BAD_REQUEST, "document-invalid",
                new LocalizedMessage("content.document.invalid"));
        errors.forEach(error -> problem.fieldErrors.putIfAbsent(error.path(), error.messageKey()));
        return problem;
    }

    /** An unknown or malformed library item id. */
    public static ContentProblemException mediaNotFound() {
        return new ContentProblemException(HttpStatus.NOT_FOUND, "media-not-found",
                new LocalizedMessage("content.media.notFound"));
    }

    /** Why a grant or registration was refused; each reason has its own message in both bundles. */
    public enum MediaRejection {
        TYPE, SIZE, CONTENT, PIXELS, UPLOAD_AGAIN
    }

    /** A grant or registration the media engine refused. */
    public static ContentProblemException mediaRejected(MediaRejection reason) {
        LocalizedMessage detail = switch (reason) {
            case TYPE -> new LocalizedMessage("content.media.rejected.type");
            case SIZE -> new LocalizedMessage("content.media.rejected.size");
            case CONTENT -> new LocalizedMessage("content.media.rejected.content");
            case PIXELS -> new LocalizedMessage("content.media.rejected.pixels");
            case UPLOAD_AGAIN -> new LocalizedMessage("content.media.rejected.uploadAgain");
        };
        return new ContentProblemException(HttpStatus.BAD_REQUEST, "content-media-rejected", detail);
    }

    /** The media engine is switched off, so no upload can be granted or kept. */
    public static ContentProblemException mediaUnavailable() {
        return new ContentProblemException(HttpStatus.SERVICE_UNAVAILABLE, "content-media-unavailable",
                new LocalizedMessage("content.media.unavailable"));
    }

    public ContentProblemException with(String name, Object value) {
        properties.put(name, value);
        return this;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public LocalizedMessage detail() {
        return detail;
    }

    public Map<String, Object> properties() {
        return Collections.unmodifiableMap(properties);
    }

    /** Field path to message bundle key. */
    public Map<String, String> fieldErrors() {
        return Collections.unmodifiableMap(fieldErrors);
    }
}
