package com.flowcortex.workflow.application;

/**
 * A request value the pure rules refuse. {@code field} is the request path the answer names
 * ({@code fields[1].text}, {@code verdict}); {@code reason} says what is wrong with it. The message never holds the
 * value: it reaches logs.
 */
final class FieldRefusal extends RuntimeException {

    enum Reason {
        /** Missing, malformed or outside the allowed set. */
        INVALID,
        /** Longer than the field allows. */
        TOO_LONG,
        /** Blank where the field needs text. */
        BLANK,
        /** A pointer the step's type does not allow, or one that does not resolve to a string. */
        NOT_EDITABLE,
        /** A text that would turn plain text into JSON structure. */
        STRUCTURE
    }

    private final String field;
    private final Reason reason;

    FieldRefusal(String field, Reason reason) {
        super(field + " " + reason);
        this.field = field;
        this.reason = reason;
    }

    String field() {
        return field;
    }

    Reason reason() {
        return reason;
    }
}
