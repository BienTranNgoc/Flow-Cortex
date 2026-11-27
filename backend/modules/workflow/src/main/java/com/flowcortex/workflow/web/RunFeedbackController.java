package com.flowcortex.workflow.web;

import com.flowcortex.workflow.application.RunFeedbacks;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/runs/{runId}/feedback} — the owner's thumbs-up or thumbs-down on a run. Always owner-scoped: another
 * member's, a deleted and an unknown run are a 404.
 */
@RestController
@RequestMapping(value = "/api/v1/runs/{runId}/feedback", produces = MediaType.APPLICATION_JSON_VALUE)
class RunFeedbackController {

    private final RunFeedbacks feedbacks;
    private final CurrentUserResolver currentUserResolver;

    RunFeedbackController(RunFeedbacks feedbacks, CurrentUserResolver currentUserResolver) {
        this.feedbacks = feedbacks;
        this.currentUserResolver = currentUserResolver;
    }

    /**
     * Replaces the run's verdict whole, so a later thumbs-up clears an earlier issue and detail. 400 names the refused
     * field ({@code verdict}, {@code issue}, {@code detail}).
     */
    @ApiResponse(responseCode = "400", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @Operation(operationId = "putRunFeedback")
    @ResponseStatus(HttpStatus.OK)
    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<RunFeedbackResponse> put(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID runId,
            @RequestBody RunFeedbackRequest request) {
        UUID callerId = currentUserResolver.require(jwt);
        return ResponseEntity.ok(RunFeedbackResponse.from(
                feedbacks.record(callerId, runId, request.verdict(), request.issue(), request.detail())));
    }
}
