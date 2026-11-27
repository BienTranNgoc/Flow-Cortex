package com.flowcortex.workflow.application;

import com.flowcortex.workflow.domain.RunStatus;
import com.flowcortex.workflow.domain.RunStep;
import com.flowcortex.workflow.domain.WorkflowRun;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * The two decisions about a run's insight that the writer and the run view must make the same way: which result is
 * a table worth reading, and whether an insight may still be on its way.
 */
final class RunInsightRules {

    /** How long after a run finished its insight is still expected; later, the page stops waiting for it. */
    static final Duration PENDING_WINDOW = Duration.ofMinutes(2);

    private RunInsightRules() {
    }

    /** The table of the last top-level step's effective output, empty when that output is not table-shaped. */
    static Optional<TableOutput> resultTable(List<RunStep> topLevelSteps) {
        return topLevelSteps.stream().max(Comparator.comparingInt(RunStep::getStepOrder))
                .flatMap(last -> TableOutput.parse(last.effectiveOutput()));
    }

    /**
     * Whether the run view says an insight is pending: the deployment switch is on, the run completed less than
     * {@link #PENDING_WINDOW} ago as a top-level run without an insight, and its result is a table. {@code optedIn}
     * reads the workflow's switch and is asked last, so a run that fails an earlier test costs no lookup.
     */
    static boolean pending(boolean deploymentOn, WorkflowRun run, List<RunStep> topLevelSteps, Instant now,
            BooleanSupplier optedIn) {
        return deploymentOn
                && run.getStatus() == RunStatus.COMPLETED
                && run.getParentRunId() == null
                && run.getInsight() == null
                && run.getFinishedAt() != null
                && now.isBefore(run.getFinishedAt().plus(PENDING_WINDOW))
                && resultTable(topLevelSteps).isPresent()
                && optedIn.getAsBoolean();
    }
}
