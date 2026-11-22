package com.flowcortex.workflow.web;

import com.flowcortex.workflow.application.WorkflowUsage;
import com.flowcortex.workflow.web.AdminWorkflowResponses.DayCountResponse;
import com.flowcortex.workflow.web.BuilderDashboardResponses.BuilderChartResponse;
import com.flowcortex.workflow.web.BuilderDashboardResponses.BuilderSummaryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/builder/dashboard} — the caller's own workflow totals and daily run chart. The owner is
 * always the token's subject; nothing in the request names a user.
 */
@RestController
@RequestMapping("/api/v1/builder/dashboard")
class BuilderDashboardController {

    private final WorkflowUsage workflowUsage;
    private final CurrentUserResolver currentUserResolver;

    BuilderDashboardController(WorkflowUsage workflowUsage, CurrentUserResolver currentUserResolver) {
        this.workflowUsage = workflowUsage;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(operationId = "getBuilderSummary")
    @GetMapping(value = "/summary", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<BuilderSummaryResponse> summary(@AuthenticationPrincipal Jwt jwt) {
        UUID ownerId = currentUserResolver.require(jwt);
        return ResponseEntity.ok(BuilderSummaryResponse.from(workflowUsage.builderSummary(ownerId)));
    }

    @Operation(operationId = "getBuilderRunsChart")
    @GetMapping(value = "/runs-chart", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<BuilderChartResponse> runsChart(@AuthenticationPrincipal Jwt jwt,
            @Parameter(schema = @Schema(allowableValues = {"7d", "30d", "90d"}, defaultValue = "30d"))
            @RequestParam(defaultValue = "30d") @Pattern(regexp = "7d|30d|90d") String period) {
        UUID ownerId = currentUserResolver.require(jwt);
        int days = Integer.parseInt(period.substring(0, period.length() - 1));
        return ResponseEntity.ok(new BuilderChartResponse(period,
                workflowUsage.builderChart(ownerId, days).stream().map(DayCountResponse::from).toList()));
    }
}
