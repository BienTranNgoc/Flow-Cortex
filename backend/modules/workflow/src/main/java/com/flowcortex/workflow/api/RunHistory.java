package com.flowcortex.workflow.api;

import java.util.UUID;

/** Read-only view of what a member has already run, for modules that must not touch workflow tables. */
public interface RunHistory {

    /** True when the member has a {@code completed} run on any workflow, including a deleted one. */
    boolean hasCompletedRun(UUID ownerId);
}
