package com.flowcortex.workflow.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Public view of a {@code workflow_run} row returned by {@link
 * WorkflowRunService}. {@code status} is the exact lowercase wire value
 * ({@code running}|{@code completed}|{@code error}). {@code feedback} is the owner's verdict and {@code insight} the
 * written reading of a table result; each is {@code null} until it exists. {@code insightPending} is true while an
 * insight may still be on its way, so a page showing the run keeps re-reading it.
 */
public record RunView(
        UUID id, UUID workflowId, UUID ownerId, String status, String input, String errorMessage,
        Instant startedAt, Instant finishedAt, List<RunStepView> steps, RunFeedbackView feedback,
        RunInsightView insight, boolean insightPending) {

    /** A run with neither feedback nor insight, for callers outside this module that build a view by hand. */
    public RunView(UUID id, UUID workflowId, UUID ownerId, String status, String input, String errorMessage,
            Instant startedAt, Instant finishedAt, List<RunStepView> steps) {
        this(id, workflowId, ownerId, status, input, errorMessage, startedAt, finishedAt, steps, null, null, false);
    }
}
