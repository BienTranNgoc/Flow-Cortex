package com.flowcortex.workflow.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One run as other modules read it. {@code workflowName} is null when the workflow is gone;
 * {@code status} is the lowercase run status; {@code titleInput} is the text a title derives
 * from (null when the run has none); {@code inputFileName} is set only for a run started with a
 * file outside Product Video; {@code steps} are ordered by {@code stepOrder}.
 */
public record RunRecord(UUID runId, UUID ownerId, UUID workflowId, String workflowName, String status,
        String titleInput, String inputFileName, Instant finishedAt, List<RunRecordStep> steps) {

    public RunRecord {
        steps = List.copyOf(steps);
    }
}
