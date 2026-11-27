package com.flowcortex.workflow.application;

import com.flowcortex.workflow.domain.RunStep;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.infrastructure.WorkflowRepository;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Builds the {@link RunViewFacts} every run view carries. It holds what the facts need beyond the run's own rows (the
 * deployment's insight switch, the clock and the workflow's insight switch), so every producer of a run view gets the
 * same answer from one place.
 */
@Component
class RunViewFactsFactory {

    private final WorkflowRepository workflowRepository;
    private final Clock clock;
    private final boolean insightEnabled;

    RunViewFactsFactory(WorkflowRepository workflowRepository, Clock clock,
            @Value("${flowcortex.workflow.run-insight.enabled:false}") boolean insightEnabled) {
        this.workflowRepository = workflowRepository;
        this.clock = clock;
        this.insightEnabled = insightEnabled;
    }

    /** {@code steps} are the run's top-level steps. */
    RunViewFacts of(WorkflowRun run, List<RunStep> steps) {
        boolean insightPending = RunInsightRules.pending(insightEnabled, run, steps, clock.instant(),
                () -> workflowRepository.findById(run.getWorkflowId()).map(Workflow::isGenerateInsight).orElse(false));
        return RunViewFacts.of(steps, insightPending);
    }
}
