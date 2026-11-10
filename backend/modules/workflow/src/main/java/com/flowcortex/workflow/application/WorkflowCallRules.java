package com.flowcortex.workflow.application;

import com.flowcortex.workflow.domain.RunStatus;
import com.flowcortex.workflow.domain.RunStep;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Pure decisions of a {@code workflow.call} step: why a child may not start, and what a finished child run means for
 * the step that waits on it. Messages are fixed English sentences shown on the run page.
 */
final class WorkflowCallRules {

    /** Deepest parent run that may still start a child; a run's own depth is 0 for a member start. */
    static final int MAX_PARENT_DEPTH = 1;

    private static final int MAX_CHILD_MESSAGE = 500;

    /** Facts the executor gathers before deciding; {@code stepTypes} is empty when the child was not found. */
    record CallFacts(UUID namedId, UUID parentWorkflowId, List<UUID> ancestorWorkflowIds, int parentDepth,
            boolean childVisible, UUID productVideoId, List<String> stepTypes, boolean firstStepRemote,
            boolean anyStepRemote, boolean anyTypeUnavailable, boolean workflowsEnabled, boolean mediaEnabled) {
    }

    /** Why the call is refused. */
    record Refusal(String code, String message) {
    }

    private record Row(Predicate<CallFacts> applies, Refusal refusal) {
    }

    private static final List<Row> REFUSALS = List.of(
            new Row(f -> f.namedId().equals(f.parentWorkflowId()) || f.ancestorWorkflowIds().contains(f.namedId()),
                    new Refusal("workflow_call_cycle", "A workflow cannot call itself or a workflow that is calling it")),
            new Row(f -> f.parentDepth() > MAX_PARENT_DEPTH,
                    new Refusal("workflow_call_too_deep", "Workflows can be nested at most three levels deep")),
            new Row(f -> !f.childVisible(),
                    new Refusal("workflow_call_not_found", "The called workflow was not found")),
            new Row(f -> f.namedId().equals(f.productVideoId()),
                    new Refusal("workflow_call_unsupported", "The Product Video workflow cannot be called from a step")),
            new Row(f -> f.stepTypes().isEmpty(),
                    new Refusal("workflow_call_child_empty", "The called workflow has no steps")),
            new Row(CallFacts::firstStepRemote,
                    new Refusal("workflow_call_needs_upload",
                            "The called workflow starts with a step that needs an upload")),
            new Row(f -> f.stepTypes().contains(KnownStepTypes.APPROVAL_CHECKPOINT),
                    new Refusal("workflow_call_has_checkpoint", "The called workflow has a review checkpoint step")),
            new Row(f -> !f.workflowsEnabled() || (f.anyStepRemote() && !f.mediaEnabled()) || f.anyTypeUnavailable(),
                    new Refusal("workflow_call_unavailable", "The called workflow cannot run right now")));

    /** What a finished (or not yet finished) child run means for the waiting step. */
    sealed interface Outcome permits Complete, Fail, NotFinished {
    }

    /** The step completes with this output (the child's last step's output, possibly null). */
    record Complete(String output) implements Outcome {
    }

    /** The step fails with this code and message. */
    record Fail(String code, String message) implements Outcome {
    }

    /** The child has not reached a final state; nothing changes. */
    record NotFinished() implements Outcome {
    }

    private WorkflowCallRules() {
    }

    /** The first refusal that applies, or null when the child may start. */
    static Refusal refusal(CallFacts facts) {
        return REFUSALS.stream().filter(row -> row.applies().test(facts)).map(Row::refusal).findFirst().orElse(null);
    }

    static Outcome outcome(RunStatus status, String errorMessage, List<RunStep> steps) {
        return switch (status) {
            case COMPLETED -> new Complete(steps.stream().max(Comparator.comparingInt(RunStep::getStepOrder))
                    .map(RunStep::getOutput).orElse(null));
            case ERROR -> new Fail("workflow_call_child_failed", childFailedMessage(errorMessage));
            case CANCELLED -> new Fail("workflow_call_child_cancelled", "The called workflow was cancelled");
            case RUNNING, WAITING_APPROVAL, CANCELLING -> new NotFinished();
        };
    }

    private static String childFailedMessage(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return "The called workflow ended in error";
        }
        String cut = errorMessage.length() > MAX_CHILD_MESSAGE ? errorMessage.substring(0, MAX_CHILD_MESSAGE)
                : errorMessage;
        return "The called workflow ended in error: " + cut;
    }
}
