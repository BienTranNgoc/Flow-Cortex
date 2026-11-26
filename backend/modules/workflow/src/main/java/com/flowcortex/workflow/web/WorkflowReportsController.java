package com.flowcortex.workflow.web;

import com.flowcortex.workflow.application.WorkflowReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/v1/workflows/{id}/report} — a member reports a workflow they can see to the platform's staff. */
@RestController
@RequestMapping("/api/v1/workflows/{id}/report")
class WorkflowReportsController {

    private final WorkflowReportService reports;
    private final CurrentUserResolver currentUserResolver;

    WorkflowReportsController(WorkflowReportService reports, CurrentUserResolver currentUserResolver) {
        this.reports = reports;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(operationId = "reportWorkflow")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "403", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ReportWorkflowResponse> report(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestBody ReportWorkflowRequest request) {
        UUID callerId = currentUserResolver.require(jwt);
        reports.report(callerId, id, request.reason(), request.details());
        return ResponseEntity.ok(new ReportWorkflowResponse(true));
    }

    record ReportWorkflowResponse(@Schema(requiredMode = RequiredMode.REQUIRED) boolean reported) {
    }
}
