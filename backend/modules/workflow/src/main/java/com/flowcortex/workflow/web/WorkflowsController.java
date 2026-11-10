package com.flowcortex.workflow.web;

import com.flowcortex.credit.api.CreditAccounts;
import com.flowcortex.workflow.api.RunOptions;
import com.flowcortex.workflow.application.WorkflowSharingService;
import com.flowcortex.workflow.api.WorkflowRunService;
import com.flowcortex.workflow.api.WorkflowService;
import com.flowcortex.workflow.api.WorkflowStepView;
import com.flowcortex.workflow.api.WorkflowView;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/workflows} — list/get (visibility: owner, or shared and published), create, update and delete
 * (owner only), {@code /api/v1/workflows/{id}/steps} — append and edit, and {@code
 * /api/v1/workflows/{id}/runs} — start. Authenticated by {@code :shared:security}'s default
 * chain (no per-module {@code SecurityFilterChain}, unlike {@code UserModuleSecurityConfig}).
 */
@RestController
@RequestMapping("/api/v1/workflows")
class WorkflowsController {

    private final WorkflowService workflowService;
    private final WorkflowRunService workflowRunService;
    private final CurrentUserResolver currentUserResolver;
    private final CreditAccounts creditAccounts;
    private final WorkflowSharingService sharingService;

    WorkflowsController(WorkflowService workflowService, WorkflowRunService workflowRunService,
            CurrentUserResolver currentUserResolver, CreditAccounts creditAccounts,
            WorkflowSharingService sharingService) {
        this.sharingService = sharingService;
        this.workflowService = workflowService;
        this.workflowRunService = workflowRunService;
        this.currentUserResolver = currentUserResolver;
        this.creditAccounts = creditAccounts;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<List<WorkflowResponse>> listWorkflows(@AuthenticationPrincipal Jwt jwt) {
        UUID currentUserId = currentUserResolver.require(jwt);
        List<WorkflowResponse> body = workflowService.listVisibleWorkflows(currentUserId).stream()
                .map(view -> WorkflowResponse.from(view, creditAccounts))
                .toList();
        return ResponseEntity.ok(body);
    }

    /** A valid {@code key} of this workflow admits a signed-in non-owner before the read, so later reads need none. */
    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "410", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ResponseStatus(HttpStatus.OK)
    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<WorkflowResponse> getWorkflow(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestParam(required = false) String key, HttpServletRequest http) {
        UUID currentUserId = currentUserResolver.require(jwt);
        admitWithKey(currentUserId, id, key, http);
        return ResponseEntity.ok(WorkflowResponse.from(workflowService.getVisibleWorkflow(currentUserId, id),
                creditAccounts));
    }

    @ApiResponse(responseCode = "400", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<WorkflowResponse> createWorkflow(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateWorkflowRequest request) {
        UUID currentUserId = currentUserResolver.require(jwt);
        WorkflowView created = workflowService.createDraft(currentUserId, AuthoringRequests.name(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(WorkflowResponse.from(created, creditAccounts));
    }

    @ApiResponse(responseCode = "400", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ResponseStatus(HttpStatus.OK)
    @PatchMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<WorkflowResponse> updateWorkflow(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody UpdateWorkflowRequest request) {
        UUID currentUserId = currentUserResolver.require(jwt);
        return ResponseEntity.ok(WorkflowResponse.from(
                workflowService.updateWorkflow(currentUserId, id, AuthoringRequests.update(request)), creditAccounts));
    }

    @ApiResponse(responseCode = "400", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @DeleteMapping("/{id}")
    ResponseEntity<Void> deleteWorkflow(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        workflowService.deleteWorkflow(currentUserResolver.require(jwt), id);
        return ResponseEntity.noContent().build();
    }

    @ApiResponse(responseCode = "400", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping(value = "/{id}/steps", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<WorkflowStepResponse> addStep(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody CreateStepRequest request) {
        UUID currentUserId = currentUserResolver.require(jwt);
        WorkflowStepView created = workflowService.addStep(currentUserId, id, AuthoringRequests.newStep(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(WorkflowStepResponse.from(created));
    }

    @ApiResponse(responseCode = "400", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ResponseStatus(HttpStatus.OK)
    @PatchMapping(value = "/{id}/steps/{stepId}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<WorkflowStepResponse> updateStep(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @PathVariable UUID stepId, @Valid @RequestBody UpdateStepRequest request) {
        UUID currentUserId = currentUserResolver.require(jwt);
        return ResponseEntity.ok(WorkflowStepResponse.from(
                workflowService.updateStep(currentUserId, id, stepId, AuthoringRequests.stepEdit(request))));
    }

    // @ResponseStatus documents 200 for springdoc/orval explicitly, rather than relying on
    // springdoc's undecorated default (runtime status is already 200 via ResponseEntity.ok).
    @ApiResponse(responseCode = "410", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ResponseStatus(HttpStatus.OK)
    @PostMapping(value = "/{id}/runs", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<RunResponse> startRun(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestParam(required = false) String key, HttpServletRequest http,
            @Valid @RequestBody StartRunRequest request) {
        UUID currentUserId = currentUserResolver.require(jwt);
        admitWithKey(currentUserId, id, key, http);
        RunOptions options = request.options() == null ? null : new RunOptions(request.options().voice());
        RunResponse body = RunResponse.from(workflowRunService.startRun(currentUserId, id, request.input(),
                request.uploadId(), request.uploadIds(), request.originalName(), options,
                Boolean.TRUE.equals(request.acknowledgeCost())), creditAccounts);
        return ResponseEntity.ok(body);
    }

    private void admitWithKey(UUID callerId, UUID workflowId, String key, HttpServletRequest http) {
        if (key != null) {
            sharingService.admitByKey(callerId, workflowId, key, WorkflowSharingController.originOf(http));
        }
    }
}
