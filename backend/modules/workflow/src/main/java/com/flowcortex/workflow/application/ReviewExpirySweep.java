package com.flowcortex.workflow.application;

import com.flowcortex.workflow.domain.ReviewTask;
import com.flowcortex.workflow.domain.RunStatus;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.infrastructure.ReviewTaskRepository;
import com.flowcortex.workflow.infrastructure.RunStepRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ends the reviews nobody decided in time, and the question writing nobody finished.
 *
 * <p>A pending task past its {@code expires_at} becomes {@code expired}; when its run still waits for
 * approval, the review step fails {@code REVIEW_EXPIRED} and the run ends in {@code error}, and a run
 * in any other status is left as it is. Each task is handled in its own transaction under the run's row
 * lock, the order a decision takes, and ended by a conditional write on {@code pending}: with several
 * instances sweeping, one wins and the rest find nothing to do.
 *
 * <p>A QA review still {@code generating} after the configured timeout (its instance died during the
 * model call) gets the standard questions. A pass takes at most {@link #BATCH_SIZE} of each kind;
 * the next pass takes the rest.
 */
@Component
public class ReviewExpirySweep {

    static final int BATCH_SIZE = 100;
    static final String CODE = "REVIEW_EXPIRED";
    static final String MESSAGE = "No one decided in time, so the run stopped.";

    private static final Logger LOG = LoggerFactory.getLogger(ReviewExpirySweep.class);

    private final ReviewTaskRepository reviewTaskRepository;
    private final RunStepRepository runStepRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final RunUpdates runUpdates;
    private final Clock clock;
    private final Duration generatingTimeout;
    private final TransactionTemplate ownTransaction;
    private final Counter expired;

    ReviewExpirySweep(ReviewTaskRepository reviewTaskRepository, RunStepRepository runStepRepository,
            WorkflowRunRepository workflowRunRepository, RunUpdates runUpdates, MeterRegistry meters, Clock clock,
            PlatformTransactionManager transactionManager,
            @Value("${flowcortex.workflow.qa-review.generating-timeout:2m}") Duration generatingTimeout) {
        if (generatingTimeout.isZero() || generatingTimeout.isNegative()) {
            throw new IllegalStateException(
                    "flowcortex.workflow.qa-review.generating-timeout must be positive: " + generatingTimeout);
        }
        this.reviewTaskRepository = reviewTaskRepository;
        this.runStepRepository = runStepRepository;
        this.workflowRunRepository = workflowRunRepository;
        this.runUpdates = runUpdates;
        this.clock = clock;
        this.generatingTimeout = generatingTimeout;
        this.ownTransaction = new TransactionTemplate(transactionManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.expired = Counter.builder("workflow.review.expired")
                .description("Review tasks the sweep ended because nobody decided in time")
                .register(meters);
    }

    /**
     * One pass; the number of review tasks this pass ended. The first tick comes one interval after
     * startup, so a restart does not sweep before the instance has settled.
     */
    @Scheduled(fixedDelayString = "${flowcortex.workflow.review.sweep-interval-ms:60000}",
            initialDelayString = "${flowcortex.workflow.review.sweep-interval-ms:60000}")
    public int sweep() {
        Instant now = clock.instant();
        int ended = 0;
        for (UUID taskId : reviewTaskRepository.findOverdueIds(now, PageRequest.of(0, BATCH_SIZE))) {
            try {
                if (Boolean.TRUE.equals(ownTransaction.execute(status -> expire(taskId, now)))) {
                    ended++;
                    expired.increment();
                }
            } catch (RuntimeException e) {
                LOG.error("Could not end overdue review: taskId={} — retrying next pass", taskId, e);
            }
        }
        for (UUID taskId : reviewTaskRepository.findStaleGeneratingIds(now.minus(generatingTimeout),
                PageRequest.of(0, BATCH_SIZE))) {
            try {
                standardQuestions(taskId);
            } catch (RuntimeException e) {
                LOG.error("Could not close question writing: taskId={} — retrying next pass", taskId, e);
            }
        }
        if (ended > 0) {
            LOG.info("workflow.review.sweep expired={}", ended);
        }
        return ended;
    }

    /** {@code true} when this call ended the task; {@code false} when a decision or another sweep did. */
    private boolean expire(UUID taskId, Instant now) {
        ReviewTask task = reviewTaskRepository.findById(taskId).orElse(null);
        if (task == null) {
            return false;
        }
        workflowRunRepository.lockById(task.getRunId());
        if (reviewTaskRepository.expireIfPending(taskId) != 1) {
            return false;
        }
        WorkflowRun run = workflowRunRepository.findById(task.getRunId()).orElse(null);
        RunStatus runStatus = run == null ? null : run.getStatus();
        if (runStatus == RunStatus.WAITING_APPROVAL) {
            if (runStepRepository.failPendingApproval(task.getRunStepId(), run.getId(), CODE, MESSAGE, now) != 1) {
                // Rolls the expiry back too: ending the task while its run keeps waiting would hide the mismatch.
                throw new IllegalStateException("Run " + run.getId() + " waits for approval but review step "
                        + task.getRunStepId() + " is not pending");
            }
            run.markError(MESSAGE, now);
            workflowRunRepository.save(run);
            runUpdates.touched(run.getId());
        }
        LOG.info("Review expired: taskId={} runId={} runStatusBefore={}", taskId, task.getRunId(), runStatus);
        return true;
    }

    private void standardQuestions(UUID taskId) {
        ReviewTask task = reviewTaskRepository.findById(taskId).orElse(null);
        if (task != null && reviewTaskRepository.storeQuestionsIfGenerating(taskId, QaQuestions.STANDARD_MARKER) == 1) {
            LOG.warn("Question writing timed out: taskId={} gets the standard questions", taskId);
            runUpdates.touched(task.getRunId());
        }
    }
}
