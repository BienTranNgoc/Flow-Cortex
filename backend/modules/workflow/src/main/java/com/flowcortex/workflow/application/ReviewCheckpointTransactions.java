package com.flowcortex.workflow.application;

import com.flowcortex.workflow.domain.RunStatus;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.domain.RunStep;
import com.flowcortex.workflow.domain.ReviewTask;
import com.flowcortex.workflow.infrastructure.ReviewTaskRepository;
import com.flowcortex.workflow.infrastructure.RunStepRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class ReviewCheckpointTransactions {

    private final ReviewTaskRepository reviewTaskRepository;
    private final RunStepRepository runStepRepository;
    private final WorkflowRunRepository workflowRunRepository;

    ReviewCheckpointTransactions(ReviewTaskRepository reviewTaskRepository,
            RunStepRepository runStepRepository, WorkflowRunRepository workflowRunRepository) {
        this.reviewTaskRepository = reviewTaskRepository;
        this.runStepRepository = runStepRepository;
        this.workflowRunRepository = workflowRunRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    boolean pause(WorkflowRun run, RunStep step, String payload, Instant now) {
        if (run.getStatus() != RunStatus.RUNNING || step.getStatus()
                != com.flowcortex.workflow.domain.RunStepStatus.PENDING) {
            return false;
        }
        if (reviewTaskRepository.findByRunStepId(step.getId()).isPresent()) {
            return false;
        }
        reviewTaskRepository.save(new ReviewTask(UUID.randomUUID(), run.getId(), step.getId(),
                "APPROVAL_CHECKPOINT", "pending", payload == null ? "" : payload, now));
        run.markWaitingApproval();
        workflowRunRepository.save(run);
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    boolean resume(ReviewTaskApprovedEvent event, Instant now) {
        ReviewTask task = reviewTaskRepository.findById(event.taskId()).orElse(null);
        if (task == null || !"approved".equals(task.getStatus())) {
            return false;
        }
        WorkflowRun run = workflowRunRepository.findById(task.getRunId()).orElse(null);
        if (run == null || run.getStatus() != RunStatus.WAITING_APPROVAL) {
            return false;
        }
        if (runStepRepository.completePendingApproval(task.getRunStepId(), run.getId(),
                event.payload(), now) != 1) {
            return false;
        }
        run.resume();
        workflowRunRepository.save(run);
        return true;
    }
}
