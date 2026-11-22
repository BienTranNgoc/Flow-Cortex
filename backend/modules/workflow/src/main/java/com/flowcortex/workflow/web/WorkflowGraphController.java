package com.flowcortex.workflow.web;

import com.flowcortex.workflow.api.WorkflowGraphs;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/v1/workflows/{id}/graph} — step structure of a workflow the caller can see. */
@RestController
@RequestMapping("/api/v1/workflows")
class WorkflowGraphController {

    private final WorkflowGraphs workflowGraphs;
    private final CurrentUserResolver currentUserResolver;

    WorkflowGraphController(WorkflowGraphs workflowGraphs, CurrentUserResolver currentUserResolver) {
        this.workflowGraphs = workflowGraphs;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(operationId = "getWorkflowGraph")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
            schema = @Schema(implementation = WorkflowGraphResponse.class)))
    @ApiResponse(responseCode = "401", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping(value = "/{id}/graph", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<WorkflowGraphResponse> getWorkflowGraph(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        UUID currentUserId = currentUserResolver.require(jwt);
        return ResponseEntity.ok(WorkflowGraphResponse.from(workflowGraphs.graphOf(currentUserId, id)));
    }
}
