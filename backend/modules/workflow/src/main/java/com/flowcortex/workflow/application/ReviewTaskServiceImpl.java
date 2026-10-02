package com.flowcortex.workflow.application;

import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.workflow.api.ReviewTaskService;
import com.flowcortex.workflow.api.ReviewTaskView;
import com.flowcortex.workflow.domain.ReviewTask;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.infrastructure.ReviewTaskRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class ReviewTaskServiceImpl implements ReviewTaskService {

    private final ReviewTaskRepository reviewTaskRepository;
    private final WorkflowRunRepository workflowRunRepository;

    ReviewTaskServiceImpl(
        ReviewTaskRepository reviewTaskRepository,
        WorkflowRunRepository workflowRunRepository
    ) {
        this.reviewTaskRepository = reviewTaskRepository;
        this.workflowRunRepository = workflowRunRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReviewTaskView> getTasksByRunId(UUID runId, UUID ownerId) {
        // IDOR guard: ensure the run exists and belongs to the caller
        WorkflowRun run = workflowRunRepository.findByIdAndOwnerId(runId, ownerId)
            .orElseThrow(() -> new NotFoundException("Workflow run not found: " + runId));

        return reviewTaskRepository.findByRunId(run.getId()).stream()
            .map(this::toView)
            .toList();
    }

    @Override
    public ReviewTaskView approveTask(UUID taskId, UUID reviewerId, String reviewedPayload) {
        ReviewTask task = reviewTaskRepository.findById(taskId)
            .orElseThrow(() -> new NotFoundException("ReviewTask not found: " + taskId));

        // IDOR guard: check ownership of the parent run
        workflowRunRepository.findByIdAndOwnerId(task.getRunId(), reviewerId)
            .orElseThrow(() -> new NotFoundException("Workflow run not found: " + task.getRunId()));

        task.approve(reviewerId, reviewedPayload);
        return toView(reviewTaskRepository.save(task));
    }

    @Override
    public ReviewTaskView rejectTask(UUID taskId, UUID reviewerId) {
        ReviewTask task = reviewTaskRepository.findById(taskId)
            .orElseThrow(() -> new NotFoundException("ReviewTask not found: " + taskId));

        // IDOR guard: check ownership of the parent run
        workflowRunRepository.findByIdAndOwnerId(task.getRunId(), reviewerId)
            .orElseThrow(() -> new NotFoundException("Workflow run not found: " + task.getRunId()));

        task.reject(reviewerId);
        return toView(reviewTaskRepository.save(task));
    }

    private ReviewTaskView toView(ReviewTask task) {
        return new ReviewTaskView(
            task.getId(),
            task.getRunId(),
            task.getRunStepId(),
            task.getReviewType(),
            task.getStatus(),
            task.getOriginalPayload(),
            task.getReviewedPayload(),
            task.getReviewedBy(),
            task.getReviewedAt(),
            task.getCreatedAt()
        );
    }
}
