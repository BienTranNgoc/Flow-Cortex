package com.flowcortex.workflow.web;

import com.flowcortex.credit.api.CreditAccounts;
import com.flowcortex.workflow.application.Caller;
import com.flowcortex.workflow.application.WorkflowAuthoring;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
 * {@code /api/v1/admin/workflows} — the same authoring writes as {@code /api/v1/workflows} for an ADMIN, who may
 * change any owner's workflow. The built-in Product Video workflow stays read-only for everyone.
 */
@RestController
@RequestMapping("/api/v1/admin/workflows")
@PreAuthorize("hasRole('ADMIN')")
class AdminWorkflowsController {

    private final WorkflowAuthoring workflowAuthoring;
    private final CurrentUserResolver currentUserResolver;
    private final CreditAccounts creditAccounts;

    AdminWorkflowsController(WorkflowAuthoring workflowAuthoring, CurrentUserResolver currentUserResolver,
            CreditAccounts creditAccounts) {
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
    @Operation(operationId = "createAdminWorkflow")
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<WorkflowResponse> createWorkflow(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateWorkflowRequest request) {
        UUID adminId = currentUserResolver.requireAdmin(jwt);
        return ResponseEntity.status(HttpStatus.CREATED).body(WorkflowResponse.from(
                workflowAuthoring.createDraft(adminId, AuthoringRequests.name(request),
                        AuthoringRequests.shared(request)), creditAccounts));
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
    @PatchMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<WorkflowResponse> updateWorkflow(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody UpdateWorkflowRequest request) {
        UUID adminId = currentUserResolver.requireAdmin(jwt);
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
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/{id}")
    ResponseEntity<Void> deleteWorkflow(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        workflowAuthoring.deleteWorkflow(Caller.admin(currentUserResolver.requireAdmin(jwt)), id);
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
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping(value = "/{id}/steps", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<WorkflowStepResponse> addStep(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody CreateStepRequest request) {
        UUID adminId = currentUserResolver.requireAdmin(jwt);
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
    @PatchMapping(value = "/{id}/steps/{stepId}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<WorkflowStepResponse> updateStep(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @PathVariable UUID stepId, @Valid @RequestBody UpdateStepRequest request) {
        UUID adminId = currentUserResolver.requireAdmin(jwt);
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
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/{id}/steps/{stepId}")
    ResponseEntity<Void> deleteStep(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @PathVariable UUID stepId) {
        workflowAuthoring.deleteStep(Caller.admin(currentUserResolver.requireAdmin(jwt)), id, stepId);
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
    @PutMapping(value = "/{id}/steps/order", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<WorkflowResponse> reorderSteps(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody ReorderStepsRequest request) {
        UUID adminId = currentUserResolver.requireAdmin(jwt);
        return ResponseEntity.ok(WorkflowResponse.from(
                workflowAuthoring.reorderSteps(Caller.admin(adminId), id, request.stepIds()), creditAccounts));
    }
}
