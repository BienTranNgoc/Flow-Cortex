package com.flowcortex.workflow.api;

/** What the member chose when starting a run, kept on the run for its steps: {@code voice} is {@code female} or {@code male}. */
public record RunOptions(String voice) {

    public static final RunOptions DEFAULTS = new RunOptions("female");
}
