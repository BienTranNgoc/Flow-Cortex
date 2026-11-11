package com.flowcortex.workflow.application;

import com.flowcortex.workflow.domain.RunStatus;
import com.flowcortex.workflow.domain.RunStepStatus;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Decides whether a finished or stopped run may be resumed, as a pure function of the run status
 * and its steps in order; the first failing check of each operation wins. The error codes below
 * are the workflow's own "a retry cannot succeed" rule, separate from the credit module's refund
 * rule.
 */
final class RunResumeRules {

    private static final Set<String> NON_RETRYABLE_CODES = Set.of("APPROVAL_REJECTED", "input_rejected", "probe_failed");

    private RunResumeRules() {}

    /** One step of the run as the arbiter sees it. */
    record StepState(UUID id, int order, String type, RunStepStatus status, String errorCode, short attempt) {}

    sealed interface Verdict permits Allowed, Refused {}

    /** The operation may proceed; {@code fromStepOrder} is the step it starts at. */
    record Allowed(int fromStepOrder) implements Verdict {}

    record Refused(Kind kind) implements Verdict {}

    enum Kind {
        RUN_NOT_FAILED,
        RUN_NOT_RESUMABLE,
        RUN_NOT_FINISHED,
        STEP_NOT_FOUND,
        STEP_NOT_FAILED,
        STEP_NOT_PENDING,
        STEP_NOT_NEXT,
        STEP_NOT_RETRYABLE,
        ATTEMPT_LIMIT,
        FROM_INVALID
    }

    /** Retry and reset of one failed step of a failed run. */
    static Verdict forRetry(RunStatus runStatus, List<StepState> steps, UUID stepId) {
        Optional<StepState> found = find(steps, stepId);
        if (found.isEmpty()) {
            return refused(Kind.STEP_NOT_FOUND);
        }
        StepState step = found.get();
        if (runStatus != RunStatus.ERROR) {
            return refused(Kind.RUN_NOT_FAILED);
        }
        if (step.status() != RunStepStatus.ERROR) {
            return refused(Kind.STEP_NOT_FAILED);
        }
        if (KnownStepTypes.APPROVAL_CHECKPOINT.equals(step.type())
                || (step.errorCode() != null && NON_RETRYABLE_CODES.contains(step.errorCode()))) {
            return refused(Kind.STEP_NOT_RETRYABLE);
        }
        if (step.attempt() >= Short.MAX_VALUE) {
            return refused(Kind.ATTEMPT_LIMIT);
        }
        return new Allowed(step.order());
    }

    /** Execute of the first step that is not completed, on a failed or running run. */
    static Verdict forExecute(RunStatus runStatus, List<StepState> steps, UUID stepId) {
        Optional<StepState> found = find(steps, stepId);
        if (found.isEmpty()) {
            return refused(Kind.STEP_NOT_FOUND);
        }
        StepState step = found.get();
        if (runStatus != RunStatus.ERROR && runStatus != RunStatus.RUNNING) {
            return refused(Kind.RUN_NOT_RESUMABLE);
        }
        if (step.status() != RunStepStatus.PENDING) {
            return refused(Kind.STEP_NOT_PENDING);
        }
        if (firstNotCompleted(steps).filter(first -> first.id().equals(step.id())).isEmpty()) {
            return refused(Kind.STEP_NOT_NEXT);
        }
        return new Allowed(step.order());
    }

    /** Retry of a whole finished run as a new run starting at {@code fromStepOrder}. */
    static Verdict forRunRetry(RunStatus runStatus, List<StepState> steps, Integer fromStepOrder) {
        if (runStatus != RunStatus.ERROR && runStatus != RunStatus.CANCELLED && runStatus != RunStatus.COMPLETED) {
            return refused(Kind.RUN_NOT_FINISHED);
        }
        int from;
        if (fromStepOrder != null) {
            from = fromStepOrder;
        } else {
            Optional<StepState> first = firstNotCompleted(steps);
            if (first.isEmpty()) {
                return refused(Kind.FROM_INVALID);
            }
            from = first.get().order();
        }
        if (from < 1 || from > steps.size()) {
            return refused(Kind.FROM_INVALID);
        }
        boolean allBeforeCompleted = steps.stream()
                .filter(step -> step.order() < from)
                .allMatch(step -> step.status() == RunStepStatus.COMPLETED);
        return allBeforeCompleted ? new Allowed(from) : refused(Kind.FROM_INVALID);
    }

    private static Optional<StepState> find(List<StepState> steps, UUID stepId) {
        return steps.stream().filter(step -> step.id().equals(stepId)).findFirst();
    }

    private static Optional<StepState> firstNotCompleted(List<StepState> steps) {
        return steps.stream().filter(step -> step.status() != RunStepStatus.COMPLETED).findFirst();
    }

    private static Refused refused(Kind kind) {
        return new Refused(kind);
    }
}
