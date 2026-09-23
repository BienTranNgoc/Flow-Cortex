package com.flowcortex.workflow.application;

import com.flowcortex.workflow.domain.RunStep;
import com.flowcortex.workflow.domain.RunStepStatus;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.domain.WorkflowStep;
import com.flowcortex.workflow.infrastructure.RunStepRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import com.flowcortex.workflow.infrastructure.WorkflowStepRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Drives a run's steps to completion AFTER the starting transaction has
 * committed — the {@code @ApplicationModuleListener} annotation composes
 * {@code @Async} + {@code @Transactional(REQUIRES_NEW)} + {@code
 * @TransactionalEventListener(phase = AFTER_COMMIT)}, and needs {@code
 * @EnableAsync} on the application (wired in {@code :boot:app}) plus the
 * persistent Event Publication Registry for delivery-loss protection.
 *
 * <p>Dispatch is strictly sequential by {@code step_order}: each step is
 * claimed atomically ({@link RunStepTransactions#claim}) before its input is
 * resolved and its work runs; a claim that loses the race (another
 * execution — e.g. a redelivered event — already has the step) simply stops
 * this invocation without side effects. A step failure (a thrown exception
 * from input resolution OR the step's own work) is recorded ({@link
 * RunStepTransactions#fail}) and halts the run — later steps stay {@code
 * pending}, never dispatched.
 */
@Component
class WorkflowRunOrchestrator {

    private static final Logger LOG = LoggerFactory.getLogger(WorkflowRunOrchestrator.class);
    private static final String MDC_RUN_ID = "runId";

    private final WorkflowRunRepository workflowRunRepository;
    private final RunStepRepository runStepRepository;
    private final WorkflowStepRepository workflowStepRepository;
    private final RunStepTransactions transactions;
    private final InputResolver inputResolver;
    private final StepDispatcher stepDispatcher;

    WorkflowRunOrchestrator(WorkflowRunRepository workflowRunRepository, RunStepRepository runStepRepository,
            WorkflowStepRepository workflowStepRepository, RunStepTransactions transactions,
            InputResolver inputResolver, StepDispatcher stepDispatcher) {
        this.workflowRunRepository = workflowRunRepository;
        this.runStepRepository = runStepRepository;
        this.workflowStepRepository = workflowStepRepository;
        this.transactions = transactions;
        this.inputResolver = inputResolver;
        this.stepDispatcher = stepDispatcher;
    }

    @ApplicationModuleListener
    void on(WorkflowRunStartedEvent event) {
        MDC.put(MDC_RUN_ID, event.runId().toString());
        try {
            execute(event.runId());
        } finally {
            MDC.remove(MDC_RUN_ID);
        }
    }

    private void execute(UUID runId) {
        WorkflowRun run = workflowRunRepository.findById(runId).orElse(null);
        if (run == null) {
            LOG.warn("Workflow run {} not found — nothing to execute (should not happen)", runId);
            return;
        }

        List<RunStep> runSteps = runStepRepository.findByRunIdOrderByStepOrderAsc(runId);
        Map<Integer, WorkflowStep> stepDefsByOrder = workflowStepRepository
                .findByWorkflowIdOrderByStepOrderAsc(run.getWorkflowId()).stream()
                .collect(Collectors.toMap(step -> (int) step.getStepOrder(), step -> step));
        Map<Integer, String> outputsByOrder = new LinkedHashMap<>();

        LOG.info("Workflow run started: {} step(s) to execute", runSteps.size());

        for (RunStep runStep : runSteps) {
            int order = runStep.getStepOrder();

            if (runStep.getStatus() == RunStepStatus.COMPLETED) {
                // Already handled by an earlier/concurrent invocation of this
                // same listener (e.g. a redelivered event racing a normal
                // run) — reuse its output and keep going.
                outputsByOrder.put(order, runStep.getOutput());
                continue;
            }
            if (runStep.getStatus() != RunStepStatus.PENDING) {
                LOG.info("Step {} already {} — another execution is handling this run, stopping here", order,
                        runStep.getStatus());
                return;
            }

            Instant claimedAt = Instant.now();
            if (!transactions.claim(runStep.getId(), claimedAt)) {
                LOG.info("Step {} claimed by another execution — stopping here", order);
                return;
            }
            LOG.info("Step {} running (type={})", order, runStep.getType());

            WorkflowStep stepDef = stepDefsByOrder.get(order);
            String resolvedInput = null;
            try {
                resolvedInput = inputResolver.resolve(order, stepDef == null ? null : stepDef.getInputRef(),
                        run.getInput(), outputsByOrder);
                String output = stepDispatcher.execute(runStep.getType(), resolvedInput);
                transactions.complete(runId, runStep.getId(), resolvedInput, output, Instant.now());
                outputsByOrder.put(order, output);
                LOG.info("Step {} completed", order);
            } catch (RuntimeException e) {
                transactions.fail(runId, runStep.getId(), resolvedInput, e.getMessage(), Instant.now());
                LOG.warn("Step {} failed: {} — run halted, later steps stay pending", order, e.getMessage());
                return;
            }
        }
        LOG.info("Workflow run finished: all steps completed");
    }
}
