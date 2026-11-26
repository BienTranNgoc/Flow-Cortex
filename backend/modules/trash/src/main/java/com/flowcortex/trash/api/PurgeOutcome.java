package com.flowcortex.trash.api;

/** What a provider answers to a purge. */
public sealed interface PurgeOutcome {

    /** The owning row is deleted for good. */
    record Purged() implements PurgeOutcome {
    }

    /** The owning row was already gone; the Trash drops its entry. */
    record Gone() implements PurgeOutcome {
    }

    /** The row cannot be deleted now (still in use); the entry stays. */
    record Blocked(String messageKey) implements PurgeOutcome {
    }
}
