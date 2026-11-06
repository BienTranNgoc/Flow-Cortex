package com.flowcortex.shared.web;

/**
 * Thrown by any module when a requested resource does not exist, or exists
 * outside the caller's own scope (a cross-scope read is a 404, not a 403, so
 * scope boundaries are not discoverable). Mapped by {@link
 * GlobalExceptionHandler} to a 404 {@code application/problem+json} response.
 */
public class NotFoundException extends RuntimeException {

    private final LocalizedMessage detail;

    public NotFoundException(LocalizedMessage detail) {
        super(detail.toString());
        this.detail = detail;
    }

    /** The bundle key for the problem detail. */
    public LocalizedMessage getDetail() {
        return detail;
    }
}
