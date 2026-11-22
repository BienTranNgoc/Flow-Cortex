package com.flowcortex.workflow.web;

import com.flowcortex.credit.api.CreditAccounts;
import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.shared.security.authz.RequirePermission;
import com.flowcortex.shared.web.audit.AdminAudit;
import com.flowcortex.workflow.application.Caller;
import com.flowcortex.workflow.application.WorkflowAuthoring;
import com.flowcortex.workflow.application.WorkflowSharingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/admin/workflows} — the same authoring writes as {@code /api/v1/workflows} for a caller holding
 * {@code workflow.workflow.manage}, who may change any owner's workflow. The built-in Product Video workflow stays read-only for everyone.
 */
@RestController
@RequestMapping("/api/v1/admin/workflows")
class AdminWorkflowsController {

    private final WorkflowAuthoring workflowAuthoring;
    private final CurrentUserResolver currentUserResolver;
    private final CreditAccounts creditAccounts;
    private final WorkflowSharingService sharingService;

    AdminWorkflowsController(WorkflowAuthoring workflowAuthoring, CurrentUserResolver currentUserResolver,
            CreditAccounts creditAccounts, WorkflowSharingService sharingService) {
        this.sharingService = sharingService;
        this.workflowAuthoring = workflowAuthoring;
        this.currentUserResolver = currentUserResolver;
        this.creditAccounts = creditAccounts;
    }

    @ApiResponse(responseCode = "400", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @Operation(operationId = "setAdminWorkflowVisibility")
    @AdminAudit(value = "workflow.set_visibility", capture = "visibility")
    @RequirePermission(Permission.WORKFLOW_WORKFLOW_MANAGE)
    @ResponseStatus(HttpStatus.OK)
    @PatchMapping(value = "/{id}/visibility", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ShareSettingsResponse> setVisibility(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody SetVisibilityRequest request, HttpServletRequest http) {
        UUID adminId = currentUserResolver.require(jwt);
        return ResponseEntity.ok(ShareSettingsResponse.from(sharingService.setVisibility(Caller.admin(adminId), id,
                request.target(), request.expiresAt(), WorkflowSharingController.originOf(http))));
    }

    @ApiResponse(responseCode = "400", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @Operation(operationId = "createAdminWorkflow")
    @AdminAudit("workflow.create")
    @RequirePermission(Permission.WORKFLOW_WORKFLOW_MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<WorkflowResponse> createWorkflow(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateWorkflowRequest request) {
        UUID adminId = currentUserResolver.require(jwt);
        return ResponseEntity.status(HttpStatus.CREATED).body(WorkflowResponse.from(
                workflowAuthoring.createDraft(adminId, AuthoringRequests.name(request)), creditAccounts));
    }

    @ApiResponse(responseCode = "400", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @Operation(operationId = "updateAdminWorkflow")
    @AdminAudit(value = "workflow.update", capture = "status")
    @RequirePermission(Permission.WORKFLOW_WORKFLOW_MANAGE)
    @ResponseStatus(HttpStatus.OK)
    @PatchMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<WorkflowResponse> updateWorkflow(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody UpdateWorkflowRequest request) {
        UUID adminId = currentUserResolver.require(jwt);
        return ResponseEntity.ok(WorkflowResponse.from(
                workflowAuthoring.updateWorkflow(Caller.admin(adminId), id, AuthoringRequests.update(request)),
                creditAccounts));
    }

    @ApiResponse(responseCode = "400", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @Operation(operationId = "deleteAdminWorkflow")
    @AdminAudit("workflow.delete")
    @RequirePermission(Permission.WORKFLOW_WORKFLOW_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/{id}")
    ResponseEntity<Void> deleteWorkflow(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        workflowAuthoring.deleteWorkflow(Caller.admin(currentUserResolver.require(jwt)), id);
        return ResponseEntity.noContent().build();
    }

    @ApiResponse(responseCode = "400", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @Operation(operationId = "addAdminWorkflowStep")
    @AdminAudit(value = "workflow_step.create", resourceParam = "", capture = "type", params = "id")
    @RequirePermission(Permission.WORKFLOW_WORKFLOW_MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping(value = "/{id}/steps", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<WorkflowStepResponse> addStep(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody CreateStepRequest request) {
        UUID adminId = currentUserResolver.require(jwt);
        return ResponseEntity.status(HttpStatus.CREATED).body(WorkflowStepResponse.from(
                workflowAuthoring.addStep(Caller.admin(adminId), id, AuthoringRequests.newStep(request))));
    }

    @ApiResponse(responseCode = "400", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @Operation(operationId = "updateAdminWorkflowStep")
    @AdminAudit(value = "workflow_step.update", resourceParam = "stepId", params = "id")
    @RequirePermission(Permission.WORKFLOW_WORKFLOW_MANAGE)
    @ResponseStatus(HttpStatus.OK)
    @PatchMapping(value = "/{id}/steps/{stepId}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<WorkflowStepResponse> updateStep(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @PathVariable UUID stepId, @Valid @RequestBody UpdateStepRequest request) {
        UUID adminId = currentUserResolver.require(jwt);
        return ResponseEntity.ok(WorkflowStepResponse.from(workflowAuthoring.updateStep(Caller.admin(adminId), id,
                stepId, AuthoringRequests.stepEdit(request))));
    }

    @ApiResponse(responseCode = "400", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @Operation(operationId = "deleteAdminWorkflowStep")
    @AdminAudit(value = "workflow_step.delete", resourceParam = "stepId", params = "id")
    @RequirePermission(Permission.WORKFLOW_WORKFLOW_MANAGE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/{id}/steps/{stepId}")
    ResponseEntity<Void> deleteStep(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @PathVariable UUID stepId) {
        workflowAuthoring.deleteStep(Caller.admin(currentUserResolver.require(jwt)), id, stepId);
        return ResponseEntity.noContent().build();
    }

    @ApiResponse(responseCode = "400", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @Operation(operationId = "reorderAdminWorkflowSteps")
    @AdminAudit("workflow.reorder_steps")
    @RequirePermission(Permission.WORKFLOW_WORKFLOW_MANAGE)
    @PutMapping(value = "/{id}/steps/order", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<WorkflowResponse> reorderSteps(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody ReorderStepsRequest request) {
        UUID adminId = currentUserResolver.require(jwt);
        return ResponseEntity.ok(WorkflowResponse.from(
                workflowAuthoring.reorderSteps(Caller.admin(adminId), id, request.stepIds()), creditAccounts));
    }
}
