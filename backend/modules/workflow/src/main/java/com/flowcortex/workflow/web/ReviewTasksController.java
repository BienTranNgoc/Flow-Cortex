package com.flowcortex.workflow.web;

import com.flowcortex.workflow.api.ReviewTaskService;
import com.flowcortex.workflow.api.ReviewTaskView;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.Valid;

@RequestMapping(value = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
@RestController
public class ReviewTasksController {

    private final ReviewTaskService reviewTaskService;
    private final CurrentUserResolver currentUserResolver;

    public ReviewTasksController(
        ReviewTaskService reviewTaskService,
        CurrentUserResolver currentUserResolver
    ) {
        this.reviewTaskService = reviewTaskService;
        this.currentUserResolver = currentUserResolver;
    }

    @GetMapping("/runs/{runId}/review-tasks")
    public ResponseEntity<List<ReviewTaskResponse>> getReviewTasks(
        @PathVariable UUID runId,
        Locale locale,
        @AuthenticationPrincipal Jwt jwt
    ) {
        UUID userId = currentUserResolver.require(jwt);
        List<ReviewTaskResponse> tasks = reviewTaskService.getTasksByRunId(runId, userId, locale).stream()
            .map(ReviewTaskResponse::from)
            .toList();
        return ResponseEntity.ok(tasks);
    }

    @ApiResponse(responseCode = "400", description = "malformed Idempotency-Key", content = @Content(
            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", content = @Content(
            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "no longer pending, or REVIEW_EXPIRED", content = @Content(
            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "pickedOption refused (errors.pickedOption), empty content, or a stopping run",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ResponseStatus(HttpStatus.OK)
    @PostMapping("/review-tasks/{taskId}/approve")
    public ResponseEntity<ReviewTaskResponse> approveTask(
        @PathVariable UUID taskId,
        @RequestBody(required = false) ApproveReviewTaskRequest request,
        @RequestHeader(name = IdempotencyKey.HEADER, required = false) String idempotencyKey,
        Locale locale,
        @AuthenticationPrincipal Jwt jwt
    ) {
        UUID userId = currentUserResolver.require(jwt);
        String key = IdempotencyKey.parse(idempotencyKey);
        String payload = request != null ? request.reviewedPayload() : null;
        UUID revisionId = request != null ? request.revisionId() : null;
        Long expectedVersion = request != null ? request.expectedVersion() : null;
        Integer pickedOption = request != null ? request.pickedOption() : null;

        ReviewTaskView approved = reviewTaskService.approveTask(taskId, userId, payload, revisionId, expectedVersion,
                pickedOption, key, locale);
        return ResponseEntity.ok(ReviewTaskResponse.from(approved));
    }

    @ApiResponse(responseCode = "400", description = "malformed Idempotency-Key or answers", content = @Content(
            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", content = @Content(
            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409",
            description = "no longer pending, REVIEW_EXPIRED, or REVIEW_QUESTIONS_NOT_READY",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422",
            description = "REVIEW_NOT_QA, an answer that does not fit (errors.answers.<id>), or a stopping run",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ResponseStatus(HttpStatus.OK)
    @PostMapping(value = "/review-tasks/{taskId}/answers", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ReviewTaskResponse> answerTask(
        @PathVariable UUID taskId,
        @Valid @RequestBody AnswerReviewTaskRequest request,
        @RequestHeader(name = IdempotencyKey.HEADER, required = false) String idempotencyKey,
        Locale locale,
        @AuthenticationPrincipal Jwt jwt
    ) {
        UUID userId = currentUserResolver.require(jwt);
        String key = IdempotencyKey.parse(idempotencyKey);
        ReviewTaskView answered = reviewTaskService.answerTask(taskId, userId, request.answers(), key, locale);
        return ResponseEntity.ok(ReviewTaskResponse.from(answered));
    }

    @PostMapping("/review-tasks/{taskId}/revisions")
    public ResponseEntity<ScriptRevisionResponse> saveRevision(@PathVariable UUID taskId,
            @Valid @RequestBody CreateScriptRevisionRequest request, @AuthenticationPrincipal Jwt jwt) {
        UUID userId = currentUserResolver.require(jwt);
        return ResponseEntity.ok(ScriptRevisionResponse.from(reviewTaskService.saveRevision(taskId, userId,
                request.content(), request.expectedVersion())));
    }

    @GetMapping("/review-tasks/{taskId}/revisions")
    public ResponseEntity<List<ScriptRevisionResponse>> getRevisions(@PathVariable UUID taskId,
            @AuthenticationPrincipal Jwt jwt) {
        UUID userId = currentUserResolver.require(jwt);
        return ResponseEntity.ok(reviewTaskService.getRevisions(taskId, userId).stream()
                .map(ScriptRevisionResponse::from).toList());
    }

    @ApiResponse(responseCode = "400", description = "malformed Idempotency-Key", content = @Content(
            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", content = @Content(
            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "no longer pending, or REVIEW_EXPIRED", content = @Content(
            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    @ResponseStatus(HttpStatus.OK)
    @PostMapping("/review-tasks/{taskId}/reject")
    public ResponseEntity<ReviewTaskResponse> rejectTask(
        @PathVariable UUID taskId,
        @RequestHeader(name = IdempotencyKey.HEADER, required = false) String idempotencyKey,
        Locale locale,
        @AuthenticationPrincipal Jwt jwt
    ) {
        UUID userId = currentUserResolver.require(jwt);
        String key = IdempotencyKey.parse(idempotencyKey);
        ReviewTaskView rejected = reviewTaskService.rejectTask(taskId, userId, key, locale);
        return ResponseEntity.ok(ReviewTaskResponse.from(rejected));
    }
}
