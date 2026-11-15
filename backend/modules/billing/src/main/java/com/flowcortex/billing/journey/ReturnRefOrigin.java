package com.flowcortex.billing.journey;

/**
 * Where the reference on a returning URL came from. Recorded instead of the value whenever the value is not
 * ours, so the three arrivals stay distinguishable without a caller's string entering the log stream.
 */
public enum ReturnRefOrigin {
    /** A reference in the shape this system mints; its value is kept. */
    OURS("ours"),
    /** The page was opened with no reference on the URL. */
    ABSENT("absent"),
    /** A reference this system could not have minted; the value is dropped. */
    NOT_OURS("not_ours");

    private final String value;

    ReturnRefOrigin(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
