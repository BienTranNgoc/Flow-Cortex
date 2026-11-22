package com.flowcortex.workflow.application;

import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.infrastructure.RunStepRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Turns a child run's end into the result of the {@code workflow.call} step that waits on it. Both
 * transitions are the step's usual compare-and-set on status and attempt, so a late or repeated
 * signal, or a step the reaper already closed, changes nothing.
 */
@Component
class WorkflowCallResults {

    private static final Logger LOG = LoggerFactory.getLogger(WorkflowCallResults.class);

    private final WorkflowRunRepository workflowRunRepository;
    private final RunStepRepository runStepRepository;
    private final RunStepTransactions transactions;
    private final RunAdvancer advancer;
    private final Clock clock;

    WorkflowCallResults(WorkflowRunRepository workflowRunRepository, RunStepRepository runStepRepository,
            RunStepTransactions transactions, RunAdvancer advancer, Clock clock) {
        this.workflowRunRepository = workflowRunRepository;
        this.runStepRepository = runStepRepository;
        this.transactions = transactions;
        this.advancer = advancer;
        this.clock = clock;
    }

    @ApplicationModuleListener
    void on(ChildRunFinishedEvent event) {
        WorkflowRun child = workflowRunRepository.findById(event.childRunId()).orElse(null);
        if (child == null || child.getParentRunId() == null || child.getParentStepId() == null
                || child.getParentStepAttempt() == null) {
            return;
        }
        Instant now = clock.instant();
        boolean applied = switch (WorkflowCallRules.outcome(child.getStatus(), child.getErrorMessage(),
                runStepRepository.findByRunIdOrderByStepOrderAsc(child.getId()))) {
            case WorkflowCallRules.Complete complete -> transactions.complete(child.getParentRunId(),
                    child.getParentStepId(), child.getParentStepAttempt(), complete.output(), null, now);
            case WorkflowCallRules.Fail fail -> transactions.fail(child.getParentRunId(), child.getParentStepId(),
                    child.getParentStepAttempt(), fail.code(), fail.message(), now);
            case WorkflowCallRules.NotFinished notFinished -> false;
        };
        if (applied) {
            LOG.info("Workflow call settled: parentRunId={} childRunId={} childStatus={}", child.getParentRunId(),
                    child.getId(), child.getStatus());
            advancer.advance(child.getParentRunId());
        }
    }
}
