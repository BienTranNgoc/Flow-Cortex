package com.flowcortex.onboarding.domain;

/** Where a member stands on the first-result report: sent, owed because a run completed, or nothing yet. */
public enum FirstResultState {
    REPORTED("reported"), PENDING("pending"), NONE("none");

    private final String wire;

    FirstResultState(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static FirstResultState of(boolean reported, boolean firstRunDone) {
        if (reported) {
            return REPORTED;
        }
        return firstRunDone ? PENDING : NONE;
    }
}
