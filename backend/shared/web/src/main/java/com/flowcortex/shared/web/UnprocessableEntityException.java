package com.flowcortex.shared.web;

import java.util.Map;

/**
 * Thrown by any module when a well-formed request cannot be carried out in the resource's current
 * state (an incomplete workflow, an expired or used one-time link). Mapped by {@link
 * GlobalExceptionHandler} to a 422 problem whose optional {@code code} is a stable machine code
 * and whose optional {@code errors} names offending fields, each optionally with a machine
 * {@code reasons} entry.
 */
public class UnprocessableEntityException extends RuntimeException {

    private final LocalizedMessage detail;
    private final String code;
    private final Map<String, LocalizedMessage> errors;

    private final Map<String, String> reasons;

    public UnprocessableEntityException(LocalizedMessage detail, String code, Map<String, LocalizedMessage> errors,
            Map<String, String> reasons) {
        super(detail.toString());
        this.detail = detail;
        this.code = code;
        this.errors = errors == null ? Map.of() : Map.copyOf(errors);
        this.reasons = reasons == null ? Map.of() : Map.copyOf(reasons);
    }

    public UnprocessableEntityException(LocalizedMessage detail, String code, Map<String, LocalizedMessage> errors) {
        this(detail, code, errors, Map.of());
    }

    public UnprocessableEntityException(LocalizedMessage detail, String code) {
        this(detail, code, Map.of());
    }

    /** The bundle key for the problem detail. */
    public LocalizedMessage getDetail() {
        return detail;
    }

    /** The machine code, or {@code null} when the detail alone is the contract. */
    public String getCode() {
        return code;
    }

    /** Field name to the bundle key of its reason. */
    public Map<String, LocalizedMessage> getErrors() {
        return errors;
    }

    /** Field name to a stable machine reason a client can branch on without parsing the translated message. */
    public Map<String, String> getReasons() {
        return reasons;
    }
}
