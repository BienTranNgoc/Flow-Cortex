package com.flowcortex.workflow.web;

import com.flowcortex.workflow.api.RunStepView;
import java.time.Instant;
import java.util.UUID;

/** {@code status} is the exact lowercase wire value: pending/running/completed/error. */
record RunStepResponse(
        UUID id, int stepOrder, String type, String status, String input, String output, String errorMessage,
        Instant startedAt, Instant finishedAt) {

    static RunStepResponse from(RunStepView view) {
        return new RunStepResponse(view.id(), view.stepOrder(), view.type(), view.status(), view.input(),
                view.output(), view.errorMessage(), view.startedAt(), view.finishedAt());
    }
}
