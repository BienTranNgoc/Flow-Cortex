package com.flowcortex.workflow.api;

/**
 * What a failed run step tells the member: the failure's {@code category}, whose fault it is
 * ({@code source}), whether running the step again can succeed and what to do next ({@code recovery}).
 * The string values are the wire values of the published failure table.
 */
public record RunStepFailure(String category, String source, boolean retryable, String recovery) {}
