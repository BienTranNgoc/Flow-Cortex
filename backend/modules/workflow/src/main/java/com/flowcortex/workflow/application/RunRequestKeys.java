package com.flowcortex.workflow.application;

import com.flowcortex.workflow.api.RunView;
import com.flowcortex.workflow.domain.RunStep;
import com.flowcortex.workflow.infrastructure.RunStepRepository;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;

/** The run an owner already started under a request key, shared by every start path that accepts one. */
final class RunRequestKeys {

    /** Long enough for the winning start to finish its object copy and commit its run. */
    private static final Duration WINNER_WAIT = Duration.ofSeconds(10);
    private static final long POLL_MILLIS = 100;

    private RunRequestKeys() {
    }

    static Optional<RunView> existing(WorkflowRunRepository runs, RunStepRepository runSteps,
            StepExecutorRegistry registry, Predicate<String> isDownloadable, UUID ownerId, String requestKey) {
        if (requestKey == null) {
            return Optional.empty();
        }
        return runs.findByOwnerIdAndRequestKey(ownerId, requestKey).map(run -> {
            List<RunStep> steps = runSteps.findByRunIdOrderByStepOrderAsc(run.getId());
            return WorkflowMapper.toRunView(run, steps, registry::isRemote, isDownloadable);
        });
    }

    /**
     * Runs a start that found no run under the key. When it loses a race to a concurrent start of the same key (a
     * unique violation on the run, or on an upload both starts register) the winner's run is returned once it
     * commits. A violation on a start without a key, or one the winner never follows, is not this race and is
     * rethrown.
     */
    static RunView orWinner(Supplier<RunView> start, WorkflowRunRepository runs, RunStepRepository runSteps,
            StepExecutorRegistry registry, Predicate<String> isDownloadable, UUID ownerId, String requestKey) {
        try {
            return start.get();
        } catch (DataIntegrityViolationException e) {
            if (requestKey == null) {
                throw e;
            }
            Instant deadline = Instant.now().plus(WINNER_WAIT);
            while (true) {
                Optional<RunView> winner = existing(runs, runSteps, registry, isDownloadable, ownerId,
                        requestKey);
                if (winner.isPresent()) {
                    return winner.get();
                }
                if (Instant.now().isAfter(deadline)) {
                    throw e;
                }
                try {
                    Thread.sleep(POLL_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }
}
