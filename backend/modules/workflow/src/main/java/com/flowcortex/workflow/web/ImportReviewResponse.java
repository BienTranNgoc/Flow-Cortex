package com.flowcortex.workflow.web;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.workflow.application.WorkflowTransferService.ReviewView;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * What an import review answers. {@code blocked} means the file cannot be imported; {@code outbound} means the
 * import needs the acknowledgment of {@code outboundTargets}. The token is valid until {@code expiresAt}.
 */
record ImportReviewResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID token,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant expiresAt,
        @Schema(requiredMode = RequiredMode.REQUIRED) String workflowName,
        @Schema(requiredMode = RequiredMode.REQUIRED) int stepCount,
        @Schema(requiredMode = RequiredMode.REQUIRED) boolean blocked,
        @Schema(requiredMode = RequiredMode.REQUIRED) boolean outbound,
        @Schema(requiredMode = RequiredMode.REQUIRED) List<ImportIssueResponse> issues,
        @Schema(requiredMode = RequiredMode.REQUIRED) List<OutboundTargetResponse> outboundTargets) {

    /** {@code text} resolves an issue's message in the request's language. */
    static ImportReviewResponse from(ReviewView view,
            Function<LocalizedMessage, String> text) {
        return new ImportReviewResponse(view.token(), view.expiresAt(), view.workflowName(), view.stepCount(),
                view.blocked(), view.outbound(),
                view.issues().stream().map(issue -> new ImportIssueResponse(issue.code(), issue.severity(),
                        issue.stepOrder(), issue.field(), text.apply(issue.message()))).toList(),
                view.outboundTargets().stream().map(target -> new OutboundTargetResponse(target.stepOrder(),
                        target.type(), target.field(), target.value())).toList());
    }
}
