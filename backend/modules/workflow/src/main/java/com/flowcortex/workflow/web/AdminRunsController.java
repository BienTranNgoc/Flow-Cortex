package com.flowcortex.workflow.web;

import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.shared.security.authz.RequirePermission;
import com.flowcortex.shared.web.PageView;
import com.flowcortex.workflow.api.WorkflowRunService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/admin} — every user's runs and every remote step, newest first, for a caller
 * holding {@code workflow.run.view}. Not owner-scoped, which is why it sits behind the admin chain
 * as well as the key check. An out-of-range {@code page}/{@code size} or an unknown {@code status} is a 400 naming the parameter.
 */
@RestController
@RequestMapping("/api/v1/admin")
class AdminRunsController {

    private static final int MAX_PAGE_SIZE = 100;
    /** Keeps {@code page * size} within an {@code int}, the offset type Spring Data pages with. */
    private static final int MAX_PAGE = Integer.MAX_VALUE / MAX_PAGE_SIZE;

    private final WorkflowRunService workflowRunService;

    AdminRunsController(WorkflowRunService workflowRunService) {
        this.workflowRunService = workflowRunService;
    }

    @Operation(operationId = "listAdminRuns")
    @RequirePermission(Permission.WORKFLOW_RUN_VIEW)
    @GetMapping(value = "/runs", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<PageView<AdminRunResponse>> listRuns(
            @RequestParam(defaultValue = "0") @Min(0) @Max(MAX_PAGE) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size,
            @Parameter(schema = @Schema(allowableValues = {"running", "waiting_approval", "completed", "error",
                    "cancelling", "cancelled"}))
            @RequestParam(required = false)
            @Pattern(regexp = "running|waiting_approval|completed|error|cancelling|cancelled") String status) {
        return ResponseEntity.ok(workflowRunService.listAllRuns(page, size, status).map(AdminRunResponse::from));
    }

    @Operation(operationId = "listAdminMediaJobs")
    @RequirePermission(Permission.WORKFLOW_RUN_VIEW)
    @GetMapping(value = "/media/jobs", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<PageView<MediaJobResponse>> listMediaJobs(
            @RequestParam(defaultValue = "0") @Min(0) @Max(MAX_PAGE) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size,
            @Parameter(schema = @Schema(allowableValues = {"pending", "running", "completed", "error"}))
            @RequestParam(required = false) @Pattern(regexp = "pending|running|completed|error") String status) {
        return ResponseEntity.ok(workflowRunService.listRemoteSteps(page, size, status).map(MediaJobResponse::from));
    }
}
