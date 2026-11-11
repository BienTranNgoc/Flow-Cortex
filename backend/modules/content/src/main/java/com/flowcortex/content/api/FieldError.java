package com.flowcortex.content.api;

/**
 * One refused field: a path such as {@code sections[3].data.body.vi} and the message key the
 * problem handler resolves per locale.
 */
public record FieldError(String path, String messageKey) {

    public static final String REQUIRED = "content.document.invalid.required";
    public static final String TOO_LONG = "content.document.invalid.tooLong";
    public static final String TOO_MANY = "content.document.invalid.tooMany";
    public static final String TOO_LARGE = "content.document.invalid.tooLarge";
    public static final String TYPE_NOT_ALLOWED = "content.document.invalid.typeNotAllowed";
    public static final String UNSUPPORTED_VERSION = "content.document.invalid.unsupportedVersion";
    public static final String MALFORMED = "content.document.invalid.malformed";
    public static final String MEDIA_NOT_FOUND = "content.document.invalid.mediaNotFound";
}
