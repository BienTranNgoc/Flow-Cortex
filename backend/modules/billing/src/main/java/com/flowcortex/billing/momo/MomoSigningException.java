package com.flowcortex.billing.momo;

/**
 * A signing string could not be built from the payload. Carries the declaration and field names only,
 * never a payload value, so it is safe to log.
 */
public class MomoSigningException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Why the string could not be built. */
    public enum Kind {
        /** The declaration names a field the payload does not carry (or carries as null). */
        MISSING_FIELD,
        /** The payload carries the field as something that is neither a string nor an integral number. */
        UNSUPPORTED_VALUE
    }

    private final Kind kind;
    private final String declaration;
    private final String field;

    public MomoSigningException(Kind kind, String declaration, String field) {
        super(kind == Kind.MISSING_FIELD
                ? "MoMo signing failed: \"" + declaration + "\" declares field \"" + field
                        + "\", which the payload does not provide"
                : "MoMo signing failed: field \"" + field + "\" of \"" + declaration
                        + "\" is not a string or an integral number");
        this.kind = kind;
        this.declaration = declaration;
        this.field = field;
    }

    public Kind kind() {
        return kind;
    }

    public String declaration() {
        return declaration;
    }

    public String field() {
        return field;
    }
}
