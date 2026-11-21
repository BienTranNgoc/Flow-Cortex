package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.workflow.application.RunResumeRules.Allowed;
import com.flowcortex.workflow.application.RunResumeRules.Kind;
import com.flowcortex.workflow.application.RunResumeRules.Refused;
import com.flowcortex.workflow.application.RunResumeRules.StepState;
import com.flowcortex.workflow.application.RunResumeRules.Verdict;
import com.flowcortex.workflow.domain.RunStatus;
import com.flowcortex.workflow.domain.RunStepStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RunResumeRulesTest {

    private static StepState step(int order, RunStepStatus status) {
        return new StepState(UUID.randomUUID(), order, "echo", status, null, (short) 1);
    }

    private static StepState failed(int order, String type, String code, short attempt) {
        return new StepState(UUID.randomUUID(), order, type, RunStepStatus.ERROR, code, attempt);
    }

    private static List<StepState> steps(RunStepStatus... statuses) {
        List<StepState> list = new ArrayList<>();
        for (int i = 0; i < statuses.length; i++) {
            list.add(step(i + 1, statuses[i]));
        }
        return list;
    }

    private static void assertRefused(Verdict verdict, Kind kind) {
        assertThat(verdict).isEqualTo(new Refused(kind));
    }

    private static void assertAllowed(Verdict verdict, int fromStepOrder) {
        assertThat(verdict).isEqualTo(new Allowed(fromStepOrder));
    }

    // retry / reset

    @Test
    void aFailedStepOfAFailedRunIsAllowed() {
        List<StepState> steps = steps(RunStepStatus.COMPLETED, RunStepStatus.ERROR, RunStepStatus.PENDING);

        assertAllowed(RunResumeRules.forRetry(RunStatus.ERROR, steps, steps.get(1).id()), 2);
    }

    @Test
    void aRunThatIsNotFailedIsRefusedFirst() {
        List<StepState> steps = steps(RunStepStatus.COMPLETED, RunStepStatus.RUNNING);

        for (RunStatus status : List.of(RunStatus.COMPLETED, RunStatus.RUNNING, RunStatus.CANCELLED)) {
            assertRefused(RunResumeRules.forRetry(status, steps, steps.get(1).id()), Kind.RUN_NOT_FAILED);
        }
    }

    @Test
    void aStepThatIsNotFailedIsRefused() {
        List<StepState> steps =
                steps(RunStepStatus.COMPLETED, RunStepStatus.RUNNING, RunStepStatus.PENDING, RunStepStatus.ERROR);

        for (int index = 0; index < 3; index++) {
            assertRefused(
                    RunResumeRules.forRetry(RunStatus.ERROR, steps, steps.get(index).id()), Kind.STEP_NOT_FAILED);
        }
    }

    @Test
    void anUnknownStepIsNotFound() {
        List<StepState> steps = steps(RunStepStatus.ERROR);

        assertRefused(RunResumeRules.forRetry(RunStatus.ERROR, steps, UUID.randomUUID()), Kind.STEP_NOT_FOUND);
        assertRefused(RunResumeRules.forRetry(RunStatus.COMPLETED, steps, UUID.randomUUID()), Kind.STEP_NOT_FOUND);
    }

    @Test
    void aCheckpointStepIsNotRetryable() {
        StepState checkpoint = failed(1, KnownStepTypes.APPROVAL_CHECKPOINT, "SOMETHING_ELSE", (short) 1);

        assertRefused(RunResumeRules.forRetry(RunStatus.ERROR, List.of(checkpoint), checkpoint.id()), Kind.STEP_NOT_RETRYABLE);
    }

    @Test
    void inputFaultsAreNotRetryable() {
        for (String code : List.of("APPROVAL_REJECTED", "input_rejected", "probe_failed")) {
            StepState step = failed(1, "echo", code, (short) 1);

            assertRefused(RunResumeRules.forRetry(RunStatus.ERROR, List.of(step), step.id()), Kind.STEP_NOT_RETRYABLE);
        }
    }

    @Test
    void insufficientCreditsAndSystemFaultsAreRetryable() {
        for (String code : new String[] {"INSUFFICIENT_CREDITS", "lease_expired", null}) {
            StepState step = failed(1, "echo", code, (short) 1);

            assertAllowed(RunResumeRules.forRetry(RunStatus.ERROR, List.of(step), step.id()), 1);
        }
    }

    @Test
    void theLastAttemptIsRefused() {
        StepState allowed = failed(1, "echo", null, (short) 32766);
        StepState last = failed(1, "echo", null, Short.MAX_VALUE);

        assertAllowed(RunResumeRules.forRetry(RunStatus.ERROR, List.of(allowed), allowed.id()), 1);
        assertRefused(RunResumeRules.forRetry(RunStatus.ERROR, List.of(last), last.id()), Kind.ATTEMPT_LIMIT);
    }

    // execute

    @Test
    void theNextPendingStepOfAFailedOrRunningRunIsAllowed() {
        List<StepState> steps = steps(RunStepStatus.COMPLETED, RunStepStatus.PENDING, RunStepStatus.PENDING);

        assertAllowed(RunResumeRules.forExecute(RunStatus.ERROR, steps, steps.get(1).id()), 2);
        assertAllowed(RunResumeRules.forExecute(RunStatus.RUNNING, steps, steps.get(1).id()), 2);
    }

    @Test
    void otherRunStatusesAreNotResumable() {
        List<StepState> steps = steps(RunStepStatus.COMPLETED, RunStepStatus.PENDING);

        for (RunStatus status :
                List.of(
                        RunStatus.COMPLETED,
                        RunStatus.WAITING_APPROVAL,
                        RunStatus.CANCELLING,
                        RunStatus.CANCELLED)) {
            assertRefused(RunResumeRules.forExecute(status, steps, steps.get(1).id()), Kind.RUN_NOT_RESUMABLE);
        }
        assertRefused(RunResumeRules.forExecute(RunStatus.CANCELLED, steps, UUID.randomUUID()), Kind.STEP_NOT_FOUND);
    }

    @Test
    void aStepThatIsNotPendingIsRefused() {
        List<StepState> steps = steps(RunStepStatus.COMPLETED, RunStepStatus.RUNNING, RunStepStatus.ERROR);

        for (int index = 0; index < 3; index++) {
            assertRefused(
                    RunResumeRules.forExecute(RunStatus.RUNNING, steps, steps.get(index).id()), Kind.STEP_NOT_PENDING);
        }
    }

    @Test
    void aPendingStepBehindAFailedStepIsNotNext() {
        List<StepState> steps = steps(RunStepStatus.COMPLETED, RunStepStatus.ERROR, RunStepStatus.PENDING);

        assertRefused(RunResumeRules.forExecute(RunStatus.ERROR, steps, steps.get(2).id()), Kind.STEP_NOT_NEXT);
    }

    // run retry

    @Test
    void defaultStartIsTheFirstStepNotCompleted() {
        List<StepState> failedRun = steps(RunStepStatus.COMPLETED, RunStepStatus.ERROR, RunStepStatus.PENDING);
        List<StepState> cancelledRun = steps(RunStepStatus.COMPLETED, RunStepStatus.SKIPPED, RunStepStatus.SKIPPED);

        assertAllowed(RunResumeRules.forRunRetry(RunStatus.ERROR, failedRun, null), 2);
        assertAllowed(RunResumeRules.forRunRetry(RunStatus.CANCELLED, cancelledRun, null), 2);
    }

    @Test
    void aRunNotFinishedIsRefused() {
        List<StepState> steps = steps(RunStepStatus.COMPLETED, RunStepStatus.PENDING);

        for (RunStatus status : List.of(RunStatus.RUNNING, RunStatus.WAITING_APPROVAL, RunStatus.CANCELLING)) {
            assertRefused(RunResumeRules.forRunRetry(status, steps, null), Kind.RUN_NOT_FINISHED);
            assertRefused(RunResumeRules.forRunRetry(status, steps, 99), Kind.RUN_NOT_FINISHED);
        }
    }

    @Test
    void aCompletedRunNeedsAStart() {
        List<StepState> steps = steps(RunStepStatus.COMPLETED, RunStepStatus.COMPLETED);

        assertRefused(RunResumeRules.forRunRetry(RunStatus.COMPLETED, steps, null), Kind.FROM_INVALID);
    }

    @Test
    void aStartOutOfRangeIsInvalid() {
        List<StepState> steps = steps(RunStepStatus.COMPLETED, RunStepStatus.ERROR);

        assertRefused(RunResumeRules.forRunRetry(RunStatus.ERROR, steps, 0), Kind.FROM_INVALID);
        assertRefused(RunResumeRules.forRunRetry(RunStatus.ERROR, steps, 3), Kind.FROM_INVALID);
    }

    @Test
    void aStartAfterAStepNotCompletedIsInvalid() {
        List<StepState> steps = steps(RunStepStatus.COMPLETED, RunStepStatus.ERROR, RunStepStatus.PENDING);

        assertRefused(RunResumeRules.forRunRetry(RunStatus.ERROR, steps, 3), Kind.FROM_INVALID);
    }

    @Test
    void theTailOfACompletedRunIsAllowed() {
        List<StepState> steps = steps(RunStepStatus.COMPLETED, RunStepStatus.COMPLETED, RunStepStatus.COMPLETED);

        assertAllowed(RunResumeRules.forRunRetry(RunStatus.COMPLETED, steps, 3), 3);
        assertAllowed(RunResumeRules.forRunRetry(RunStatus.COMPLETED, steps, 1), 1);
    }
}
