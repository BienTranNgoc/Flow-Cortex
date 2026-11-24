package com.flowcortex.workflow.application;

import com.flowcortex.workflow.domain.RunStatus;
import com.flowcortex.workflow.domain.RunStep;
import com.flowcortex.workflow.domain.RunStepStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Pure step-selection arbiter evaluated in rule order:
 * (1) block non-running runs -> (2) Product Video parallel clips -> (3) sequential default.
 */
final class ReadyStepSelector {

    sealed interface Decision permits Decision.Halt, Decision.Wait, Decision.CompleteRun,
            Decision.PauseForApproval, Decision.Dispatch {

        record Halt() implements Decision {
        }

        record Wait() implements Decision {
        }

        record CompleteRun() implements Decision {
        }

        record PauseForApproval(RunStep step) implements Decision {
        }

        record Dispatch(List<RunStep> steps) implements Decision {
        }
    }

    private record Context(
            boolean parallelClipsEnabled,
            UUID workflowId,
            RunStatus runStatus,
            List<RunStep> steps,
            ProductVideoClipPair.Pair clipPair) {
    }

    private record Rule(String name, Predicate<Context> when, Function<Context, Decision> decide) {
    }

    private static final List<Rule> ARBITER = List.of(
            new Rule("block-stopped-run",
                    ctx -> ctx.runStatus() != RunStatus.RUNNING,
                    ctx -> new Decision.Halt()),
            new Rule("product-video-parallel-clips",
                    ReadyStepSelector::inParallelClipWindow,
                    ReadyStepSelector::decideParallelClips),
            new Rule("sequential-default",
                    ctx -> true,
                    ReadyStepSelector::decideSequential));

    private ReadyStepSelector() {
    }

    static Decision select(boolean parallelClipsEnabled, UUID workflowId, RunStatus runStatus, List<RunStep> steps) {
        ProductVideoClipPair.Pair clipPair = parallelClipsEnabled
                ? ProductVideoClipPair.match(workflowId, steps).orElse(null)
                : null;
        Context ctx = new Context(parallelClipsEnabled, workflowId, runStatus, steps, clipPair);
        for (Rule rule : ARBITER) {
            if (rule.when().test(ctx)) {
                return rule.decide().apply(ctx);
            }
        }
        throw new IllegalStateException("No step selection rule matched");
    }

    private static boolean inParallelClipWindow(Context ctx) {
        ProductVideoClipPair.Pair pair = ctx.clipPair();
        if (!ctx.parallelClipsEnabled() || pair == null || !pair.prerequisitesCompleted()) {
            return false;
        }
        return pair.firstClip().getStatus() != RunStepStatus.COMPLETED
                || pair.secondClip().getStatus() != RunStepStatus.COMPLETED;
    }

    private static Decision decideParallelClips(Context ctx) {
        ProductVideoClipPair.Pair pair = ctx.clipPair();
        RunStep first = pair.firstClip();
        RunStep second = pair.secondClip();
        if (isStoppedOrFailed(first) || isStoppedOrFailed(second)) {
            return new Decision.Halt();
        }
        List<RunStep> ready = new ArrayList<>(2);
        if (first.getStatus() == RunStepStatus.PENDING) {
            ready.add(first);
        }
        if (second.getStatus() == RunStepStatus.PENDING) {
            ready.add(second);
        }
        return ready.isEmpty() ? new Decision.Wait() : new Decision.Dispatch(List.copyOf(ready));
    }

    private static boolean isStoppedOrFailed(RunStep step) {
        return step.getStatus() == RunStepStatus.ERROR || step.getStatus() == RunStepStatus.SKIPPED;
    }

    private static Decision decideSequential(Context ctx) {
        for (RunStep step : ctx.steps()) {
            if (step.getStatus() == RunStepStatus.COMPLETED) {
                continue;
            }
            if (step.getStatus() != RunStepStatus.PENDING) {
                return new Decision.Wait();
            }
            if (HumanSteps.TYPES.contains(step.getType())) {
                return new Decision.PauseForApproval(step);
            }
            return new Decision.Dispatch(List.of(step));
        }
        return new Decision.CompleteRun();
    }
}
