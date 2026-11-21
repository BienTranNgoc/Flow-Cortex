package com.flowcortex.workflow.api;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** Who besides the owner can read a workflow: nobody, holders of its link, or every member. */
public enum WorkflowVisibility {
    PRIVATE,
    UNLISTED,
    PUBLIC;

    /** The lowercase wire and database form. */
    @JsonValue
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
