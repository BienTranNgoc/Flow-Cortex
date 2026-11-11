package com.flowcortex.workflow.web;

import com.flowcortex.credit.api.CreditAccounts;
import com.flowcortex.workflow.api.WorkflowRunService;
import com.flowcortex.workflow.application.RunCancellation;
import com.flowcortex.workflow.application.RunResumption;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.bind.annotation.ResponseStatus;
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
    private final RunCancellation runCancellation;
    private final RunResumption runResumption;
    private final CurrentUserResolver currentUserResolver;
    private final CreditAccounts creditAccounts;

    RunsController(WorkflowRunService workflowRunService, RunCancellation runCancellation,
            RunResumption runResumption, CurrentUserResolver currentUserResolver,
            CreditAccounts creditAccounts) {
        this.workflowRunService = workflowRunService;
        this.runCancellation = runCancellation;
        this.runResumption = runResumption;
        this.currentUserResolver = currentUserResolver;
        this.creditAccounts = creditAccounts;
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<RunResponse> getRun(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        UUID currentUserId = currentUserResolver.require(jwt);
        return ResponseEntity.ok(RunResponse.from(workflowRunService.getRun(currentUserId, id), creditAccounts));
    }

    /**
     * Stops the caller's run: 200 with the run ({@code cancelling} while a step is still in flight,
     * else {@code cancelled}; a repeat changes nothing), 409 once it finished, 404 if not theirs.
     */
    @PostMapping(value = "/{id}/cancel", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<RunResponse> cancelRun(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        UUID currentUserId = currentUserResolver.require(jwt);
        return ResponseEntity.ok(RunResponse.from(runCancellation.cancelRun(currentUserId, id), creditAccounts));
    }

    /** Re-arms the caller's failed step for another attempt and reopens the run; 409 when not retryable. */
    @ApiResponse(responseCode = "401", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(value = "/{id}/steps/{stepId}/retry", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.OK)
    ResponseEntity<RunResponse> retryRunStep(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @PathVariable UUID stepId) {
        UUID currentUserId = currentUserResolver.require(jwt);
        return ResponseEntity.ok(RunResponse.from(runResumption.retryStep(currentUserId, id, stepId), creditAccounts));
    }

    /** Re-arms the failed step without dispatching it; the run stays stopped until the step is executed. */
    @ApiResponse(responseCode = "401", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(value = "/{id}/steps/{stepId}/reset", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.OK)
    ResponseEntity<RunResponse> resetRunStep(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @PathVariable UUID stepId) {
        UUID currentUserId = currentUserResolver.require(jwt);
        return ResponseEntity.ok(RunResponse.from(runResumption.resetStep(currentUserId, id, stepId), creditAccounts));
    }

    /** Dispatches the run's next pending step, reopening a failed run. */
    @ApiResponse(responseCode = "401", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(value = "/{id}/steps/{stepId}/execute", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.OK)
    ResponseEntity<RunResponse> executeRunStep(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @PathVariable UUID stepId) {
        UUID currentUserId = currentUserResolver.require(jwt);
        return ResponseEntity.ok(RunResponse.from(runResumption.executeStep(currentUserId, id, stepId), creditAccounts));
    }

    /** Starts a new run that reuses the finished run's completed steps and re-executes the rest. */
    @ApiResponse(responseCode = "400", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "402", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(value = "/{id}/retry", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.OK)
    ResponseEntity<RunResponse> retryRun(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody(required = false) RetryRunRequest request) {
        UUID currentUserId = currentUserResolver.require(jwt);
        Integer from = request == null ? null : request.fromStepOrder();
        boolean acknowledged = request != null && Boolean.TRUE.equals(request.acknowledgeCost());
        return ResponseEntity.ok(RunResponse.from(runResumption.retryRun(currentUserId, id, from, acknowledged), creditAccounts));
    }

    @GetMapping(value = "/{id}/steps/{order}/outputs/{name}/url", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<OutputUrlResponse> getOutputUrl(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @PathVariable int order, @PathVariable String name) {
        UUID currentUserId = currentUserResolver.require(jwt);
        return ResponseEntity.ok(OutputUrlResponse.from(workflowRunService.getOutputUrl(currentUserId, id, order, name)));
    }
}
