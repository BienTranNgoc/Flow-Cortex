package com.flowcortex.trash.api;

/** What a provider answers to a restore. */
public sealed interface RestoreOutcome {

    /** The owning row is live again. */
    record Restored() implements RestoreOutcome {
    }

    /** The owning row no longer exists or is no longer trashed; the Trash drops its entry. */
    record Gone() implements RestoreOutcome {
    }

    /** The row cannot come back now (a name taken, a parent still deleted); the entry stays. */
    record Conflict(String messageKey) implements RestoreOutcome {
    }
}
