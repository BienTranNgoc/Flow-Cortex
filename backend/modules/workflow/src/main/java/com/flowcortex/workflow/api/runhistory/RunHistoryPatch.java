package com.flowcortex.workflow.api.runhistory;

/**
 * A partial change to a run. A sent {@code title} is normalized, and a sent {@code null} or empty one clears
 * it; a {@code null} {@code favorite} leaves the mark as it is.
 */
public record RunHistoryPatch(boolean titleSent, String title, Boolean favorite) {

    public boolean isEmpty() {
        return !titleSent && favorite == null;
    }
}
