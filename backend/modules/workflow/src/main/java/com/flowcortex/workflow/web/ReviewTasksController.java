package com.flowcortex.workflow.web;

import com.flowcortex.workflow.api.ReviewTaskService;
import com.flowcortex.workflow.api.ReviewTaskView;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
        @AuthenticationPrincipal Jwt jwt
    ) {
        UUID userId = currentUserResolver.require(jwt);
        List<ReviewTaskResponse> tasks = reviewTaskService.getTasksByRunId(runId, userId).stream()
            .map(ReviewTaskResponse::from)
            .toList();
        return ResponseEntity.ok(tasks);
    }

    @PostMapping("/review-tasks/{taskId}/approve")
    public ResponseEntity<ReviewTaskResponse> approveTask(
        @PathVariable UUID taskId,
        @RequestBody(required = false) ApproveReviewTaskRequest request,
        @AuthenticationPrincipal Jwt jwt
    ) {
        UUID userId = currentUserResolver.require(jwt);
        String payload = (request != null) ? request.reviewedPayload() : null;

        ReviewTaskView approved = reviewTaskService.approveTask(taskId, userId, payload);
        return ResponseEntity.ok(ReviewTaskResponse.from(approved));
    }

    @PostMapping("/review-tasks/{taskId}/reject")
    public ResponseEntity<ReviewTaskResponse> rejectTask(
        @PathVariable UUID taskId,
        @AuthenticationPrincipal Jwt jwt
    ) {
        UUID userId = currentUserResolver.require(jwt);
        ReviewTaskView rejected = reviewTaskService.rejectTask(taskId, userId);
        return ResponseEntity.ok(ReviewTaskResponse.from(rejected));
    }
}
