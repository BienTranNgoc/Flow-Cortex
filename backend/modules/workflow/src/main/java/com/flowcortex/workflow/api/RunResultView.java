package com.flowcortex.workflow.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A completed run as the member-result read port shows it. {@code textInput} is the run's input
 * text, or {@code null} when the first step is remote (the run's input is then only the cleaned
 * name of the uploaded file).
 */
public record RunResultView(UUID runId, UUID ownerId, String workflowName, String textInput, Instant finishedAt,
        List<Step> steps) {

    /** One step of the run; {@code mode} is {@code inline} or {@code remote}, {@code status} lowercase. */
    public record Step(UUID stepId, int stepOrder, String type, String mode, String status, String output,
            Instant finishedAt, List<Output> outputs) {
    }

    /** A downloadable object a step stored, with where it lives; for server-side callers only. */
    public record Output(String name, String bucket, String objectKey, String mime, long bytes) {
    }
}
