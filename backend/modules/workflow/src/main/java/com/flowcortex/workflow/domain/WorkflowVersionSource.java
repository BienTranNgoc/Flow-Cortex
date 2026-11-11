package com.flowcortex.workflow.domain;

/** Who produced a version; always assigned by the server, never taken from a caller. */
public enum WorkflowVersionSource {
    MANUAL,
    PUBLISH,
    ROLLBACK,
    HOTFIX;

    /** Only versions carrying a user-typed note may be renamed. */
    public boolean renameable() {
        return this == MANUAL || this == HOTFIX;
    }
}
