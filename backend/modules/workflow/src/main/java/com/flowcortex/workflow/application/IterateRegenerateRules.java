package com.flowcortex.workflow.application;

import com.flowcortex.workflow.api.IterateStepConfig;
import com.flowcortex.workflow.application.RunResumeRules.StepState;
import com.flowcortex.workflow.domain.RunStatus;
import com.flowcortex.workflow.domain.RunStepStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Decides whether one child of a completed iterate step may be regenerated, as a pure function of
 * the run status, the step and its current children; the first refusal that applies wins.
 */
final class IterateRegenerateRules {

    private IterateRegenerateRules() {
    }

    /** One child of the step's current attempt. */
    record Child(UUID id, int itemIndex, RunStepStatus status, short attempt) {
    }

    sealed interface Verdict permits ReArm, Redispatch, Refused {
    }

    /** Move the child to its next attempt; {@code attempt} is the one it holds now, the guard of the move. */
    record ReArm(UUID childId, short attempt) implements Verdict {
    }

    /** The child is still pending from an earlier regenerate: dispatch it again without a new attempt. */
    record Redispatch(UUID childId) implements Verdict {
    }

    record Refused(Kind kind) implements Verdict {
    }

    enum Kind {
        STEP_NOT_TOP_LEVEL,
        NOT_ITERATE,
        RUN_NOT_COMPLETED,
        STEP_NOT_COMPLETED,
        ITEM_NOT_FOUND,
        CHILD_RUNNING,
        BUSY,
        ATTEMPT_LIMIT
    }

    /**
     * {@code step} is the run's top-level step the caller named, empty when there is none. A skipped
     * child never sits under a completed iterate step, so it is treated as no child to regenerate.
     */
    static Verdict verdict(RunStatus runStatus, Optional<StepState> step, List<Child> children, int itemIndex,
            int effectiveConcurrency) {
        if (effectiveConcurrency < 1) {
            throw new IllegalArgumentException("effectiveConcurrency must be at least 1");
        }
        if (step.isEmpty()) {
            return refused(Kind.STEP_NOT_TOP_LEVEL);
        }
        if (!IterateStepConfig.TYPE.equals(step.get().type())) {
            return refused(Kind.NOT_ITERATE);
        }
        if (runStatus != RunStatus.COMPLETED) {
            return refused(Kind.RUN_NOT_COMPLETED);
        }
        if (step.get().status() != RunStepStatus.COMPLETED) {
            return refused(Kind.STEP_NOT_COMPLETED);
        }
        Optional<Child> found = children.stream().filter(child -> child.itemIndex() == itemIndex).findFirst();
        if (found.isEmpty() || found.get().status() == RunStepStatus.SKIPPED) {
            return refused(Kind.ITEM_NOT_FOUND);
        }
        Child child = found.get();
        if (child.status() == RunStepStatus.RUNNING) {
            return refused(Kind.CHILD_RUNNING);
        }
        long othersActive = children.stream()
                .filter(other -> other != child)
                .filter(other -> other.status() == RunStepStatus.RUNNING || other.status() == RunStepStatus.PENDING)
                .count();
        if (othersActive >= effectiveConcurrency) {
            return refused(Kind.BUSY);
        }
        if (child.status() == RunStepStatus.PENDING) {
            return new Redispatch(child.id());
        }
        if (child.attempt() >= Short.MAX_VALUE) {
            return refused(Kind.ATTEMPT_LIMIT);
        }
        return new ReArm(child.id(), child.attempt());
    }

    private static Refused refused(Kind kind) {
        return new Refused(kind);
    }
}
