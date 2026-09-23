package com.flowcortex.shared.web;

/**
 * Thrown by any module when a request would violate a uniqueness or state
 * constraint (e.g. a duplicate email). Mapped by {@link
 * GlobalExceptionHandler} to a 409 {@code application/problem+json} response.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
