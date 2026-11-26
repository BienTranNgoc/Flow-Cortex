package com.flowcortex.workflow.web;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The body of a workflow report. No bean constraint: every presence, enum, trim and length rule is
 * {@code ModerationRules.checkReport}, which answers 422 naming the field. The reporter is the caller, never a field here.
 */
record ReportWorkflowRequest(
        @Schema(allowableValues = {"spam", "harmful", "scam", "inappropriate", "copyright", "pii_data_leak",
                "misleading", "other"}) String reason,
        @Schema(maxLength = 1000, nullable = true) String details) {
}
