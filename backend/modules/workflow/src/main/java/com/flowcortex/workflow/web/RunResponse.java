package com.flowcortex.workflow.web;

import com.flowcortex.credit.api.CreditAccounts;
import com.flowcortex.workflow.api.RunView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code status} is the exact lowercase wire value: running/waiting_approval/cancelling/cancelled/
 * completed/error. Returned by the start-run endpoint (freshly `running`, all steps `pending`), the
 * run-read endpoint (whatever the poll observes) and the stop endpoint. {@code chargedCredits} is
 * what the run has cost so far, charges minus refunds; {@code null} while credits are switched off.
 */
record RunResponse(
        UUID id, UUID workflowId, String status, String input, String errorMessage, Instant startedAt,
        Instant finishedAt, List<RunStepResponse> steps, Long chargedCredits) {

    static RunResponse from(RunView view, CreditAccounts credits) {
        return new RunResponse(view.id(), view.workflowId(), view.status(), view.input(), view.errorMessage(),
                view.startedAt(), view.finishedAt(), view.steps().stream().map(RunStepResponse::from).toList(),
                credits.enabled() ? credits.chargedForRun(view.id()) : null);
    }
}
