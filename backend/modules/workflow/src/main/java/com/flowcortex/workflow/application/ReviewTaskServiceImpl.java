package com.flowcortex.workflow.application;

import com.flowcortex.workflow.api.ReviewTaskService;
import com.flowcortex.workflow.domain.ReviewTask;
import com.flowcortex.workflow.infrastructure.ReviewTaskRepository;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class ReviewTaskServiceImpl implements ReviewTaskService {

    private final ReviewTaskRepository reviewTaskRepository;

    ReviewTaskServiceImpl(ReviewTaskRepository reviewTaskRepository) {
        this.reviewTaskRepository = reviewTaskRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReviewTask> getTasksByRunId(UUID runId) {
        return reviewTaskRepository.findByRunId(runId);
    }

    @Override
    public ReviewTask approveTask(UUID taskId, UUID reviewerId, String reviewedPayload) {
        ReviewTask task = reviewTaskRepository.findById(taskId)
            .orElseThrow(() -> new NoSuchElementException("ReviewTask not found: " + taskId));

        task.approve(reviewerId, reviewedPayload);
        return reviewTaskRepository.save(task);
    }

    @Override
    public ReviewTask rejectTask(UUID taskId, UUID reviewerId) {
        ReviewTask task = reviewTaskRepository.findById(taskId)
            .orElseThrow(() -> new NoSuchElementException("ReviewTask not found: " + taskId));

        task.reject(reviewerId);
        return reviewTaskRepository.save(task);
    }
}
