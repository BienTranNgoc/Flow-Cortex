package com.flowcortex.workflow.web;

import com.flowcortex.workflow.api.ReviewTaskService;
import com.flowcortex.workflow.domain.ReviewTask;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ReviewTasksController {

    private final ReviewTaskService reviewTaskService;

    public ReviewTasksController(ReviewTaskService reviewTaskService) {
        this.reviewTaskService = reviewTaskService;
    }

    @GetMapping("/runs/{runId}/review-tasks")
    public ResponseEntity<List<ReviewTaskResponse>> getReviewTasks(@PathVariable UUID runId) {
        List<ReviewTaskResponse> tasks = reviewTaskService.getTasksByRunId(runId).stream()
            .map(ReviewTaskResponse::from)
            .toList();
        return ResponseEntity.ok(tasks);
    }

    @PostMapping("/review-tasks/{taskId}/approve")
    public ResponseEntity<ReviewTaskResponse> approveTask(
        @PathVariable UUID taskId,
        @RequestBody(required = false) ApproveReviewTaskRequest request
    ) {
        UUID reviewerId = UUID.randomUUID(); // Sẽ map vào Principal/JWT khi tích hợp auth
        String payload = (request != null) ? request.reviewedPayload() : null;

        ReviewTask approved = reviewTaskService.approveTask(taskId, reviewerId, payload);
        return ResponseEntity.ok(ReviewTaskResponse.from(approved));
    }

    @PostMapping("/review-tasks/{taskId}/reject")
    public ResponseEntity<ReviewTaskResponse> rejectTask(@PathVariable UUID taskId) {
        UUID reviewerId = UUID.randomUUID();
        ReviewTask rejected = reviewTaskService.rejectTask(taskId, reviewerId);
        return ResponseEntity.ok(ReviewTaskResponse.from(rejected));
    }
}
