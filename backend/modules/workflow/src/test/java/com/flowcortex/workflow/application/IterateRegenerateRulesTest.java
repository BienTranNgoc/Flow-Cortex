package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.workflow.application.IterateRegenerateRules.Child;
import com.flowcortex.workflow.application.IterateRegenerateRules.Kind;
import com.flowcortex.workflow.application.IterateRegenerateRules.ReArm;
import com.flowcortex.workflow.application.IterateRegenerateRules.Redispatch;
import com.flowcortex.workflow.application.IterateRegenerateRules.Refused;
import com.flowcortex.workflow.application.IterateRegenerateRules.Verdict;
import com.flowcortex.workflow.application.RunResumeRules.StepState;
import com.flowcortex.workflow.domain.RunStatus;
import com.flowcortex.workflow.domain.RunStepStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IterateRegenerateRulesTest {

    private static StepState iterate(RunStepStatus status) {
        return new StepState(new UUID(1, 1), 2, "iterate", status, null, (short) 1);
    }

    private static Child child(int index, RunStepStatus status) {
        return new Child(new UUID(0, index), index, status, (short) 1);
    }

    private static List<Child> threeCompleted() {
        return List.of(child(0, RunStepStatus.COMPLETED), child(1, RunStepStatus.COMPLETED),
                child(2, RunStepStatus.COMPLETED));
    }

    private static Verdict verdict(RunStatus run, Optional<StepState> step, List<Child> children, int item, int c) {
        return IterateRegenerateRules.verdict(run, step, children, item, c);
    }

    private static Verdict regenerate(List<Child> children, int item, int c) {
        return verdict(RunStatus.COMPLETED, Optional.of(iterate(RunStepStatus.COMPLETED)), children, item, c);
    }

    @Test
    void stepNotTopLevel() {
        assertThat(verdict(RunStatus.COMPLETED, Optional.empty(), threeCompleted(), 1, 3))
                .isEqualTo(new Refused(Kind.STEP_NOT_TOP_LEVEL));
    }

    @Test
    void notIterate() {
        StepState echo = new StepState(new UUID(1, 1), 2, "echo", RunStepStatus.COMPLETED, null, (short) 1);

        assertThat(verdict(RunStatus.COMPLETED, Optional.of(echo), threeCompleted(), 1, 3))
                .isEqualTo(new Refused(Kind.NOT_ITERATE));
    }

    @Test
    void runNotCompleted() {
        for (RunStatus status : List.of(RunStatus.RUNNING, RunStatus.ERROR, RunStatus.CANCELLED)) {
            assertThat(verdict(status, Optional.of(iterate(RunStepStatus.COMPLETED)), threeCompleted(), 1, 3))
                    .isEqualTo(new Refused(Kind.RUN_NOT_COMPLETED));
        }
    }

    @Test
    void stepNotCompleted() {
        assertThat(verdict(RunStatus.COMPLETED, Optional.of(iterate(RunStepStatus.ERROR)), threeCompleted(), 1, 3))
                .isEqualTo(new Refused(Kind.STEP_NOT_COMPLETED));
    }

    @Test
    void itemNotFound() {
        assertThat(regenerate(threeCompleted(), 3, 3)).isEqualTo(new Refused(Kind.ITEM_NOT_FOUND));
        assertThat(regenerate(threeCompleted(), -1, 3)).isEqualTo(new Refused(Kind.ITEM_NOT_FOUND));
        assertThat(regenerate(List.of(), 0, 3)).isEqualTo(new Refused(Kind.ITEM_NOT_FOUND));
    }

    @Test
    void childRunning() {
        List<Child> children = List.of(child(0, RunStepStatus.COMPLETED), child(1, RunStepStatus.RUNNING));

        assertThat(regenerate(children, 1, 3)).isEqualTo(new Refused(Kind.CHILD_RUNNING));
    }

    @Test
    void busyAtTheEffectiveConcurrency() {
        List<Child> children = List.of(child(0, RunStepStatus.RUNNING), child(1, RunStepStatus.PENDING),
                child(2, RunStepStatus.COMPLETED), child(3, RunStepStatus.COMPLETED));

        assertThat(regenerate(children, 2, 2)).isEqualTo(new Refused(Kind.BUSY));
        assertThat(regenerate(children, 2, 3)).isEqualTo(new ReArm(new UUID(0, 2), (short) 1));
    }

    @Test
    void attemptLimit() {
        Child last = new Child(new UUID(0, 1), 1, RunStepStatus.COMPLETED, Short.MAX_VALUE);

        assertThat(regenerate(List.of(child(0, RunStepStatus.COMPLETED), last), 1, 3))
                .isEqualTo(new Refused(Kind.ATTEMPT_LIMIT));
    }

    @Test
    void theFirstMatchingRefusalWins() {
        assertThat(verdict(RunStatus.COMPLETED, Optional.of(iterate(RunStepStatus.ERROR)), List.of(), 9, 3))
                .isEqualTo(new Refused(Kind.STEP_NOT_COMPLETED));
        assertThat(verdict(RunStatus.COMPLETED, Optional.of(iterate(RunStepStatus.COMPLETED)),
                List.of(child(0, RunStepStatus.RUNNING)), 0, 1)).isEqualTo(new Refused(Kind.CHILD_RUNNING));
        Child busyAtLimit = new Child(new UUID(0, 1), 1, RunStepStatus.COMPLETED, Short.MAX_VALUE);
        assertThat(regenerate(List.of(child(0, RunStepStatus.RUNNING), busyAtLimit), 1, 1))
                .isEqualTo(new Refused(Kind.BUSY));
    }

    @Test
    void runRunningAndChildRunningIsRunNotCompleted() {
        assertThat(verdict(RunStatus.RUNNING, Optional.of(iterate(RunStepStatus.COMPLETED)),
                List.of(child(0, RunStepStatus.RUNNING)), 0, 3)).isEqualTo(new Refused(Kind.RUN_NOT_COMPLETED));
    }

    @Test
    void completedChildIsReArmed() {
        Child second = new Child(new UUID(0, 1), 1, RunStepStatus.COMPLETED, (short) 4);

        assertThat(regenerate(List.of(child(0, RunStepStatus.COMPLETED), second), 1, 3))
                .isEqualTo(new ReArm(new UUID(0, 1), (short) 4));
    }

    @Test
    void failedChildIsReArmed() {
        assertThat(regenerate(List.of(child(0, RunStepStatus.ERROR)), 0, 3))
                .isEqualTo(new ReArm(new UUID(0, 0), (short) 1));
    }

    @Test
    void pendingChildIsRedispatchedWithoutANewAttempt() {
        List<Child> children = List.of(child(0, RunStepStatus.RUNNING), child(1, RunStepStatus.PENDING));
        Child pendingAtLimit = new Child(new UUID(0, 2), 2, RunStepStatus.PENDING, Short.MAX_VALUE);

        assertThat(regenerate(children, 1, 2)).isEqualTo(new Redispatch(new UUID(0, 1)));
        assertThat(regenerate(List.of(child(0, RunStepStatus.PENDING)), 0, 1)).isEqualTo(new Redispatch(new UUID(0, 0)));
        assertThat(regenerate(List.of(pendingAtLimit), 2, 1)).isEqualTo(new Redispatch(new UUID(0, 2)));
    }

    @Test
    void aSkippedChildCannotBeRegenerated() {
        assertThat(regenerate(List.of(child(0, RunStepStatus.SKIPPED)), 0, 3))
                .isEqualTo(new Refused(Kind.ITEM_NOT_FOUND));
    }

    @Test
    void anEffectiveConcurrencyBelowOneIsRefused() {
        assertThatThrownBy(() -> regenerate(threeCompleted(), 1, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
