package com.flowcortex.workflow.web;

import com.flowcortex.workflow.application.RunStepEdits;
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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/runs/{runId}/steps/{stepId}/edit} — the owner correcting the text of a finished step's result:
 * edit, undo to the previous edit, discard the edit. Always owner-scoped: another member's or an unknown run, and a
 * step that is not a top-level step of it, are a 404.
 */
@RestController
@RequestMapping(value = "/api/v1/runs/{runId}/steps/{stepId}/edit", produces = MediaType.APPLICATION_JSON_VALUE)
class RunStepEditsController {

    private final RunStepEdits edits;
    private final CurrentUserResolver currentUserResolver;

    RunStepEditsController(RunStepEdits edits, CurrentUserResolver currentUserResolver) {
        this.edits = edits;
        this.currentUserResolver = currentUserResolver;
    }

    /**
     * Applies each text at its pointer onto the stored original; a field not sent keeps its current value. 400 names
     * the refused field ({@code fields}, {@code fields[i].pointer}, {@code fields[i].text}, {@code expectedRevision});
     * 409 is {@code step_not_editable} or {@code output_revision_conflict}.
     */
    @ApiResponse(responseCode = "400", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "step_not_editable or output_revision_conflict",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @Operation(operationId = "editRunStepOutput")
    @ResponseStatus(HttpStatus.OK)
    @PatchMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<StepEditResponse> edit(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID runId,
            @PathVariable UUID stepId, @Valid @RequestBody EditStepOutputRequest request) {
        UUID callerId = currentUserResolver.require(jwt);
        return ResponseEntity.ok(StepEditResponse.from(
                edits.edit(callerId, runId, stepId, request.toEdits(), request.expectedRevision())));
    }

    /** Makes the previous stored edit current, or clears the edit when there is only one. 409 {@code nothing_to_undo}. */
    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "step_not_editable or nothing_to_undo",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @Operation(operationId = "undoRunStepEdit")
    @ResponseStatus(HttpStatus.OK)
    @PostMapping("/undo")
    ResponseEntity<StepEditResponse> undo(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID runId,
            @PathVariable UUID stepId) {
        UUID callerId = currentUserResolver.require(jwt);
        return ResponseEntity.ok(StepEditResponse.from(edits.undo(callerId, runId, stepId)));
    }

    /** Clears the current edit and keeps the history; the same answer when no edit exists. */
    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @Operation(operationId = "discardRunStepEdit")
    @ResponseStatus(HttpStatus.OK)
    @DeleteMapping
    ResponseEntity<StepEditResponse> discard(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID runId,
            @PathVariable UUID stepId) {
        UUID callerId = currentUserResolver.require(jwt);
        return ResponseEntity.ok(StepEditResponse.from(edits.discard(callerId, runId, stepId)));
    }
}
