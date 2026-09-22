package com.flowcortex.workflow.web;

import com.flowcortex.workflow.api.WorkflowRunService;
import com.flowcortex.workflow.api.WorkflowService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/workflows} — list/get (visibility: owner or shared, R-049)
 * and {@code /api/v1/workflows/{id}/runs} — start (design.md D5/R-050).
 * Plainly authenticated by {@code :shared:security}'s default chain — no
 * per-module {@code SecurityFilterChain} needed here (design.md D4 sync,
 * unlike {@code :modules:user}'s {@code UserModuleSecurityConfig}).
 *
 * <p>Package-private, depends only on this module's own {@code api} port
 * plus {@code user :: api} (via {@link CurrentUserResolver}) — design.md D3.
 */
@RestController
@RequestMapping("/api/v1/workflows")
class WorkflowsController {

    private final WorkflowService workflowService;
    private final WorkflowRunService workflowRunService;
    private final CurrentUserResolver currentUserResolver;

    WorkflowsController(WorkflowService workflowService, WorkflowRunService workflowRunService,
            CurrentUserResolver currentUserResolver) {
        this.workflowService = workflowService;
        this.workflowRunService = workflowRunService;
        this.currentUserResolver = currentUserResolver;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<List<WorkflowResponse>> listWorkflows(@AuthenticationPrincipal Jwt jwt) {
        UUID currentUserId = currentUserResolver.require(jwt);
        List<WorkflowResponse> body = workflowService.listVisibleWorkflows(currentUserId).stream()
                .map(WorkflowResponse::from)
                .toList();
        return ResponseEntity.ok(body);
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<WorkflowResponse> getWorkflow(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        UUID currentUserId = currentUserResolver.require(jwt);
        return ResponseEntity.ok(WorkflowResponse.from(workflowService.getVisibleWorkflow(currentUserId, id)));
    }

    // @ResponseStatus documents 200 for springdoc/orval (J-5 oracle: the start
    // call returns 200 while the run is still `running` — task 5.7 lane
    // delta) — the runtime status already IS 200 (ResponseEntity.ok below);
    // this only makes the generated OpenAPI contract explicit rather than
    // relying on springdoc's undecorated default (slice-4 discovery).
    @ResponseStatus(HttpStatus.OK)
    @PostMapping(value = "/{id}/runs", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<RunResponse> startRun(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody StartRunRequest request) {
        UUID currentUserId = currentUserResolver.require(jwt);
        RunResponse body = RunResponse.from(workflowRunService.startRun(currentUserId, id, request.input()));
        return ResponseEntity.ok(body);
    }
}
