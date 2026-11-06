package com.flowcortex.shared.web;

/**
 * Thrown by any module when a request would violate a uniqueness or state
 * constraint (e.g. a duplicate email). Mapped by {@link
 * GlobalExceptionHandler} to a 409 {@code application/problem+json} response.
 */
public class ConflictException extends RuntimeException {

    private final LocalizedMessage detail;

    public ConflictException(LocalizedMessage detail) {
        super(detail.toString());
        this.detail = detail;
    }

    /** The bundle key for the problem detail. */
    public LocalizedMessage getDetail() {
        return detail;
    }
}
