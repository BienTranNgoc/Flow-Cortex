package com.flowcortex.workflow.web;

import com.flowcortex.workflow.api.RunView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code status} is the exact lowercase wire value (design.md D1): running/completed/error. Returned by both the
 * start-run endpoint (freshly `running`, all steps `pending`) and the run-read endpoint (whatever the poll observes).
 */
record RunResponse(
        UUID id, UUID workflowId, String status, String input, String errorMessage, Instant startedAt,
        Instant finishedAt, List<RunStepResponse> steps) {

    static RunResponse from(RunView view) {
        return new RunResponse(view.id(), view.workflowId(), view.status(), view.input(), view.errorMessage(),
                view.startedAt(), view.finishedAt(), view.steps().stream().map(RunStepResponse::from).toList());
    }
}
