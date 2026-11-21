package com.flowcortex.workflow.web;

import com.flowcortex.workflow.api.BuilderSummaryView;
import com.flowcortex.workflow.web.AdminWorkflowResponses.DayCountResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;

/** Wire shapes of the member's builder dashboard: counts only, never another member's identity. */
final class BuilderDashboardResponses {

    private BuilderDashboardResponses() {
    }

    /** {@code totalRuns} counts every run of the caller's workflows, whoever started it. */
    record BuilderSummaryResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) long totalWorkflows,
            @Schema(requiredMode = RequiredMode.REQUIRED) long publishedWorkflows,
            @Schema(requiredMode = RequiredMode.REQUIRED) long totalRuns) {

        static BuilderSummaryResponse from(BuilderSummaryView view) {
            return new BuilderSummaryResponse(view.totalWorkflows(), view.publishedWorkflows(), view.totalRuns());
        }
    }

    record BuilderChartResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"7d", "30d", "90d"}) String period,
            @Schema(requiredMode = RequiredMode.REQUIRED) List<DayCountResponse> series) {
    }
}
