package com.flowcortex.shared.web;

/**
 * Thrown by any module when a requested resource does not exist, or exists
 * outside the caller's own scope (a cross-scope read is a 404, not a 403, so
 * scope boundaries are not discoverable). Mapped by {@link
 * GlobalExceptionHandler} to a 404 {@code application/problem+json} response.
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
