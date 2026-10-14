package com.flowcortex.workflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.workflow.api.ProductVideoWorkflow;
import com.flowcortex.workflow.domain.RunStatus;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.domain.RunStep;
import com.flowcortex.workflow.domain.ReviewTask;
import com.flowcortex.workflow.domain.ReviewTaskStatus;
import com.flowcortex.workflow.domain.ScriptRevision;
import com.flowcortex.workflow.infrastructure.ReviewTaskRepository;
import com.flowcortex.workflow.infrastructure.RunStepRepository;
import com.flowcortex.workflow.infrastructure.ScriptRevisionRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class ReviewCheckpointTransactions {

    private final ReviewTaskRepository reviewTaskRepository;
    private final RunStepRepository runStepRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final ScriptRevisionRepository scriptRevisionRepository;
    /**
     * Its own plain mapper, like {@link InputResolver}: this module runs on Jackson 2 while Boot 4's
     * context publishes a Jackson 3 {@code tools.jackson} bean, so injecting one would not resolve.
     */
    private final ObjectMapper json = new ObjectMapper();

    ReviewCheckpointTransactions(ReviewTaskRepository reviewTaskRepository,
            RunStepRepository runStepRepository, WorkflowRunRepository workflowRunRepository,
            ScriptRevisionRepository scriptRevisionRepository) {
        this.reviewTaskRepository = reviewTaskRepository;
        this.runStepRepository = runStepRepository;
        this.workflowRunRepository = workflowRunRepository;
        this.scriptRevisionRepository = scriptRevisionRepository;
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
        String reviewPayload = isProductVideo(run) ? scriptFrom(payload) : payload;
        ReviewTask task = reviewTaskRepository.save(new ReviewTask(UUID.randomUUID(), run.getId(), step.getId(),
                "APPROVAL_CHECKPOINT", reviewPayload, now));
        if (isProductVideo(run)) {
            ScriptRevision revision = scriptRevisionRepository.save(new ScriptRevision(UUID.randomUUID(), run.getId(),
                    task.getId(), 1, reviewPayload, null, now));
            task.attachRevision(revision.getId(), task.getVersion() == null ? 0L : task.getVersion());
            reviewTaskRepository.save(task);
        }
        run.markWaitingApproval();
        workflowRunRepository.save(run);
        return true;
    }

    private static boolean isProductVideo(WorkflowRun run) {
        return ProductVideoWorkflow.ID.equals(run.getWorkflowId());
    }

    private String scriptFrom(String payload) {
        try {
            JsonNode script = json.readTree(payload).get("script");
            if (script == null || !script.isTextual() || script.textValue().isBlank()) {
                throw new IllegalArgumentException("generate_script result has no script");
            }
            return script.textValue();
        } catch (Exception exception) {
            throw new IllegalArgumentException("Product Video script result is invalid", exception);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    boolean resume(ReviewTaskApprovedEvent event, Instant now) {
        ReviewTask task = reviewTaskRepository.findById(event.taskId()).orElse(null);
        if (task == null || task.getStatus() != ReviewTaskStatus.APPROVED) {
            return false;
        }
        WorkflowRun run = workflowRunRepository.findById(task.getRunId()).orElse(null);
        if (run == null || run.getStatus() != RunStatus.WAITING_APPROVAL) {
            return false;
        }
        if (runStepRepository.completePendingApproval(task.getRunStepId(), run.getId(),
                stepOutput(run, event.payload()), now) != 1) {
            return false;
        }
        run.resume();
        workflowRunRepository.save(run);
        return true;
    }

    /**
     * The approved Product Video script is handed to {@code generate_speech}, whose step reads
     * {@code {step:2.script}} — so the checkpoint step's output must stay the provider result shape
     * that {@link #scriptFrom} unwrapped at pause time. Every other checkpoint keeps the payload as
     * reviewed.
     */
    private String stepOutput(WorkflowRun run, String payload) {
        if (!isProductVideo(run)) {
            return payload;
        }
        try {
            return json.writeValueAsString(Map.of("script", payload));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Approved Product Video script is not serializable", e);
        }
    }
}
