package com.flowcortex.workflow.web;

import com.flowcortex.credit.api.CreditAccounts;
import com.flowcortex.workflow.api.RunView;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code status} is the exact lowercase wire value: running/waiting_approval/cancelling/cancelled/
 * completed/error. Returned by the start-run endpoint (freshly `running`, all steps `pending`), the
 * run-read endpoint (whatever the poll observes) and the stop endpoint. {@code chargedCredits} is
 * what the run has cost so far, charges minus refunds; {@code null} while credits are switched off. {@code feedback}
 * and {@code insight} are null until the owner gave a verdict and until an insight was written;
 * {@code insightPending} is true while one may still be on its way.
 */
record RunResponse(
        UUID id, UUID workflowId, String status, String input, String errorMessage, Instant startedAt,
        Instant finishedAt, List<RunStepResponse> steps, Long chargedCredits, RunFeedbackResponse feedback,
        RunInsightResponse insight, @Schema(requiredMode = RequiredMode.REQUIRED) boolean insightPending) {

    static RunResponse from(RunView view, CreditAccounts credits) {
        return new RunResponse(view.id(), view.workflowId(), view.status(), view.input(), view.errorMessage(),
                view.startedAt(), view.finishedAt(), view.steps().stream().map(RunStepResponse::from).toList(),
                credits.enabled() ? credits.chargedForRun(view.id()) : null,
                RunFeedbackResponse.from(view.feedback()), RunInsightResponse.from(view.insight()),
                view.insightPending());
    }
}
