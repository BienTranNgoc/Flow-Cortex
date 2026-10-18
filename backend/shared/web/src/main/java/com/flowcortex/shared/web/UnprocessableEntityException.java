package com.flowcortex.shared.web;

import java.util.Map;

/**
 * Thrown by any module when a well-formed request cannot be carried out in the resource's current
 * state (an incomplete workflow, an expired or used one-time link). Mapped by {@link
 * GlobalExceptionHandler} to a 422 problem whose optional {@code code} is a stable machine code
 * and whose optional {@code errors} names offending fields.
 */
public class UnprocessableEntityException extends RuntimeException {

    private final String code;
    private final Map<String, String> errors;

    public UnprocessableEntityException(String detail, String code, Map<String, String> errors) {
        super(detail);
        this.code = code;
        this.errors = errors == null ? Map.of() : Map.copyOf(errors);
    }

    public UnprocessableEntityException(String detail, String code) {
        this(detail, code, Map.of());
    }

    /** The machine code, or {@code null} when the detail alone is the contract. */
    public String getCode() {
        return code;
    }

    public Map<String, String> getErrors() {
        return errors;
    }
}
