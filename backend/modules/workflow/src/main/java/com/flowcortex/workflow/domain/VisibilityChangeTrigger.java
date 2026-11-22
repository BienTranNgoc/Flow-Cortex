package com.flowcortex.workflow.domain;

/** What caused a visibility audit entry. */
public enum VisibilityChangeTrigger {
    OWNER,
    ADMIN,
    KEY_REGENERATED
}
