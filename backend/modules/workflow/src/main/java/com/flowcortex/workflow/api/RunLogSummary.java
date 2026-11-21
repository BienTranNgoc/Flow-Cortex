package com.flowcortex.workflow.api;

/** Counts over every step of the run, whatever level or limit the log was read with. */
public record RunLogSummary(int errorCount, int warningCount, String firstError) {
}
