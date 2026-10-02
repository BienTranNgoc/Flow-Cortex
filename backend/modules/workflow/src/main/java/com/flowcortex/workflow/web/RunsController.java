package com.flowcortex.workflow.web;

import com.flowcortex.workflow.api.WorkflowRunService;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/runs/{id}} — read a run's status + steps, ALWAYS
 * owner-scoped (even for a run of a shared workflow definition) — unknown or
 * foreign run id is a 404, not an empty 200.
 */
@RestController
@RequestMapping("/api/v1/runs")
class RunsController {

    private final WorkflowRunService workflowRunService;
    private final CurrentUserResolver currentUserResolver;

    RunsController(WorkflowRunService workflowRunService, CurrentUserResolver currentUserResolver) {
        this.workflowRunService = workflowRunService;
        this.currentUserResolver = currentUserResolver;
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<RunResponse> getRun(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        UUID currentUserId = currentUserResolver.require(jwt);
        return ResponseEntity.ok(RunResponse.from(workflowRunService.getRun(currentUserId, id)));
    }

    @GetMapping(value = "/{id}/steps/{order}/outputs/{name}/url", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<OutputUrlResponse> getOutputUrl(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @PathVariable int order, @PathVariable String name) {
        UUID currentUserId = currentUserResolver.require(jwt);
        return ResponseEntity.ok(OutputUrlResponse.from(workflowRunService.getOutputUrl(currentUserId, id, order, name)));
    }
}
