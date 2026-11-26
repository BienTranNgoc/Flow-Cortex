package com.flowcortex.workflow.api.batch;

import com.fasterxml.jackson.annotation.JsonValue;

/** The declared layout of a batch's {@code content}; leaving it out means the layout is detected. */
public enum BatchFormat {
    CSV("csv"),
    JSON("json"),
    JSONL("jsonl");

    private final String wire;

    BatchFormat(String wire) {
        this.wire = wire;
    }

    /** The lowercase name a request carries. */
    @JsonValue
    public String wire() {
        return wire;
    }
}
