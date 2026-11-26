package com.flowcortex.workflow.web;

import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.shared.security.authz.RequirePermission;
import com.flowcortex.shared.web.PageView;
import com.flowcortex.shared.web.audit.AdminAudit;
import com.flowcortex.workflow.application.ModerationRules.Action;
import com.flowcortex.workflow.application.WorkflowModerationService;
import com.flowcortex.workflow.web.ModerationRequests.ModerationRequest;
import com.flowcortex.workflow.web.ModerationResponses.WorkflowReportDetailResponse;
import com.flowcortex.workflow.web.ModerationResponses.WorkflowReportRowResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/admin/workflow-reports} — the queue of member reports and the five actions staff take on one. List
 * and detail need {@code moderation.report.view}; dismiss and resolve {@code moderation.report.resolve}; hide, remove
 * and restore need that key and {@code moderation.content.hide}, since they also change the workflow. An
 * out-of-range {@code page}/{@code size}, an unknown {@code status} or an over-long {@code q} is a 400 naming the
 * parameter. Each write is audited by action and report id, never by its reason or note.
 */
@RestController
@RequestMapping("/api/v1/admin/workflow-reports")
@ApiResponse(responseCode = "400", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
        schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
        schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "409", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
        schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "422", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
        schema = @Schema(implementation = ProblemDetail.class)))
class AdminWorkflowReportQueueController {

    private static final int MAX_PAGE_SIZE = 100;
    /** Keeps {@code page * size} within an {@code int}, the offset type the page query uses. */
    private static final int MAX_PAGE = Integer.MAX_VALUE / MAX_PAGE_SIZE;
    private static final int MAX_QUERY_LENGTH = 255;

    private final WorkflowModerationService moderation;
    private final CurrentUserResolver currentUserResolver;

    AdminWorkflowReportQueueController(WorkflowModerationService moderation,
            CurrentUserResolver currentUserResolver) {
        this.moderation = moderation;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(operationId = "listWorkflowReports")
    @ApiResponse(responseCode = "200")
    @RequirePermission(Permission.MODERATION_REPORT_VIEW)
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<PageView<WorkflowReportRowResponse>> list(
            @RequestParam(defaultValue = "0") @Min(0) @Max(MAX_PAGE) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size,
            @Parameter(schema = @Schema(allowableValues = {"pending", "dismissed", "resolved", "hidden", "removed"}))
            @RequestParam(required = false)
            @Pattern(regexp = "pending|dismissed|resolved|hidden|removed") String status,
            @RequestParam(required = false) @Size(max = MAX_QUERY_LENGTH) String q) {
        return ResponseEntity.ok(moderation.list(status, q, page, size).map(WorkflowReportRowResponse::from));
    }

    @Operation(operationId = "getWorkflowReport")
    @ApiResponse(responseCode = "200")
    @RequirePermission(Permission.MODERATION_REPORT_VIEW)
    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<WorkflowReportDetailResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok(WorkflowReportDetailResponse.from(moderation.reportDetail(id)));
    }

    @Operation(operationId = "dismissWorkflowReport")
    @ApiResponse(responseCode = "200")
    @AdminAudit("workflow_report.dismiss")
    @RequirePermission(Permission.MODERATION_REPORT_RESOLVE)
    @PostMapping(value = "/{id}/dismiss", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<WorkflowReportDetailResponse> dismiss(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestBody(required = false) ModerationRequest request) {
        return act(jwt, id, Action.DISMISS, request);
    }

    @Operation(operationId = "resolveWorkflowReport")
    @ApiResponse(responseCode = "200")
    @AdminAudit("workflow_report.resolve")
    @RequirePermission(Permission.MODERATION_REPORT_RESOLVE)
    @PostMapping(value = "/{id}/resolve", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<WorkflowReportDetailResponse> resolve(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestBody(required = false) ModerationRequest request) {
        return act(jwt, id, Action.RESOLVE, request);
    }

    @Operation(operationId = "hideWorkflowReport")
    @ApiResponse(responseCode = "200")
    @AdminAudit("workflow_report.hide")
    @RequirePermission({Permission.MODERATION_REPORT_RESOLVE, Permission.MODERATION_CONTENT_HIDE})
    @PostMapping(value = "/{id}/hide", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<WorkflowReportDetailResponse> hide(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestBody(required = false) ModerationRequest request) {
        return act(jwt, id, Action.HIDE, request);
    }

    @Operation(operationId = "removeWorkflowReport")
    @ApiResponse(responseCode = "200")
    @AdminAudit("workflow_report.remove")
    @RequirePermission({Permission.MODERATION_REPORT_RESOLVE, Permission.MODERATION_CONTENT_HIDE})
    @PostMapping(value = "/{id}/remove", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<WorkflowReportDetailResponse> remove(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestBody(required = false) ModerationRequest request) {
        return act(jwt, id, Action.REMOVE, request);
    }

    @Operation(operationId = "restoreWorkflowReport")
    @ApiResponse(responseCode = "200")
    @AdminAudit("workflow_report.restore")
    @RequirePermission({Permission.MODERATION_REPORT_RESOLVE, Permission.MODERATION_CONTENT_HIDE})
    @PostMapping(value = "/{id}/restore", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<WorkflowReportDetailResponse> restore(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestBody(required = false) ModerationRequest request) {
        return act(jwt, id, Action.RESTORE, request);
    }

    private ResponseEntity<WorkflowReportDetailResponse> act(Jwt jwt, UUID reportId, Action action,
            ModerationRequest request) {
        UUID staffId = currentUserResolver.require(jwt);
        ModerationRequest body = ModerationRequest.orEmpty(request);
        moderation.actOnReport(staffId, reportId, action, body.reason(), body.internalNote());
        return ResponseEntity.ok(WorkflowReportDetailResponse.from(moderation.reportDetail(reportId)));
    }
}
