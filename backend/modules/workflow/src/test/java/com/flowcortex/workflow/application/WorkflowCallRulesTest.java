package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.workflow.domain.RunStatus;
import com.flowcortex.workflow.domain.RunStep;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The refusal order and the outcome of a finished child run, as pure functions. */
class WorkflowCallRulesTest {

    private static final UUID PARENT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID GRAND = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID NAMED = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID PRODUCT_VIDEO = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    private static Facts valid() {
        return new Facts();
    }

    private static String code(Facts facts) {
        WorkflowCallRules.Refusal refusal = WorkflowCallRules.refusal(facts.build());
        return refusal == null ? null : refusal.code();
    }

    private static final class Facts {
        UUID named = NAMED;
        List<UUID> ancestors = List.of();
        int depth;
        boolean visible = true;
        List<String> types = List.of("echo", "echo");
        boolean firstRemote;
        boolean anyRemote;
        boolean typeUnavailable;
        boolean workflowsEnabled = true;
        boolean mediaEnabled = true;

        Facts withNamed(UUID v) {
            named = v;
            return this;
        }

        Facts withAncestors(List<UUID> v) {
            ancestors = v;
            return this;
        }

        Facts withDepth(int v) {
            depth = v;
            return this;
        }

        Facts withVisible(boolean v) {
            visible = v;
            return this;
        }

        Facts withTypes(List<String> v) {
            types = v;
            return this;
        }

        Facts withFirstRemote(boolean v) {
            firstRemote = v;
            return this;
        }

        Facts withRemoteStep(boolean v) {
            anyRemote = v;
            return this;
        }

        Facts withTypeUnavailable(boolean v) {
            typeUnavailable = v;
            return this;
        }

        Facts withWorkflowsEnabled(boolean v) {
            workflowsEnabled = v;
            return this;
        }

        Facts withMediaEnabled(boolean v) {
            mediaEnabled = v;
            return this;
        }

        WorkflowCallRules.CallFacts build() {
            return new WorkflowCallRules.CallFacts(named, PARENT, ancestors, depth, visible, PRODUCT_VIDEO, types,
                    firstRemote, anyRemote, typeUnavailable, workflowsEnabled, mediaEnabled);
        }
    }

    @Test
    void aValidCallIsNotRefused() {
        assertThat(code(valid())).isNull();
    }

    @Test
    void namingTheParentWorkflowIsACycle() {
        assertThat(code(valid().withNamed(PARENT))).isEqualTo("workflow_call_cycle");
    }

    @Test
    void namingAWorkflowAboveTheParentIsACycle() {
        assertThat(code(valid().withAncestors(List.of(GRAND)).withDepth(1).withNamed(GRAND)))
                .isEqualTo("workflow_call_cycle");
    }

    @Test
    void aParentAtDepthTwoIsTooDeep() {
        assertThat(code(valid().withDepth(2))).isEqualTo("workflow_call_too_deep");
    }

    @Test
    void aParentAtDepthOnePasses() {
        assertThat(code(valid().withDepth(1))).isNull();
    }

    @Test
    void aCycleThatIsAlsoTooDeepReportsTheCycle() {
        assertThat(code(valid().withDepth(2).withNamed(PARENT))).isEqualTo("workflow_call_cycle");
    }

    @Test
    void aWorkflowTheOwnerCannotSeeIsNotFound() {
        assertThat(code(valid().withVisible(false))).isEqualTo("workflow_call_not_found");
    }

    @Test
    void theProductVideoWorkflowIsUnsupported() {
        assertThat(code(valid().withNamed(PRODUCT_VIDEO))).isEqualTo("workflow_call_unsupported");
    }

    @Test
    void aChildWithoutStepsIsEmpty() {
        assertThat(code(valid().withTypes(List.of()))).isEqualTo("workflow_call_child_empty");
    }

    @Test
    void aChildStartingWithARemoteStepNeedsAnUpload() {
        assertThat(code(valid().withFirstRemote(true))).isEqualTo("workflow_call_needs_upload");
    }

    @Test
    void aChildWithAReviewCheckpointIsRefused() {
        assertThat(code(valid().withTypes(List.of("echo", "APPROVAL_CHECKPOINT"))))
                .isEqualTo("workflow_call_has_checkpoint");
    }

    @Test
    void workflowsSwitchedOffIsUnavailable() {
        assertThat(code(valid().withWorkflowsEnabled(false))).isEqualTo("workflow_call_unavailable");
    }

    @Test
    void mediaSwitchedOffWithARemoteStepIsUnavailable() {
        assertThat(code(valid().withMediaEnabled(false).withTypes(List.of("echo", "probe")).withRemoteStep(true)))
                .isEqualTo("workflow_call_unavailable");
    }

    @Test
    void mediaSwitchedOffWithoutARemoteStepPasses() {
        assertThat(code(valid().withMediaEnabled(false))).isNull();
    }

    @Test
    void anUnavailableStepTypeIsUnavailable() {
        assertThat(code(valid().withTypeUnavailable(true))).isEqualTo("workflow_call_unavailable");
    }

    @Test
    void aCompletedChildGivesItsLastStepsOutput() {
        WorkflowCallRules.Outcome outcome = WorkflowCallRules.outcome(RunStatus.COMPLETED, null,
                List.of(step(2, "two"), step(1, "one")));

        assertThat(outcome).isEqualTo(new WorkflowCallRules.Complete("two"));
    }

    @Test
    void aCompletedChildWhoseLastOutputIsNullCompletesWithNull() {
        WorkflowCallRules.Outcome outcome = WorkflowCallRules.outcome(RunStatus.COMPLETED, null,
                List.of(step(1, "one"), step(2, null)));

        assertThat(outcome).isEqualTo(new WorkflowCallRules.Complete(null));
    }

    @Test
    void anErroredChildFailsTheStepWithItsMessage() {
        WorkflowCallRules.Outcome outcome = WorkflowCallRules.outcome(RunStatus.ERROR,
                "Not enough credits to run this step", List.of());

        assertThat(outcome).isInstanceOf(WorkflowCallRules.Fail.class);
        WorkflowCallRules.Fail fail = (WorkflowCallRules.Fail) outcome;
        assertThat(fail.code()).isEqualTo("workflow_call_child_failed");
        assertThat(fail.message()).contains("Not enough credits to run this step");
    }

    @Test
    void aLongChildMessageIsCutToFiveHundredCharacters() {
        WorkflowCallRules.Fail fail = (WorkflowCallRules.Fail) WorkflowCallRules.outcome(RunStatus.ERROR,
                "x".repeat(600), List.of());

        assertThat(fail.message()).contains("x".repeat(500)).doesNotContain("x".repeat(501));
    }

    @Test
    void anErroredChildWithoutAMessageStillFails() {
        WorkflowCallRules.Fail fail = (WorkflowCallRules.Fail) WorkflowCallRules.outcome(RunStatus.ERROR, null,
                List.of());

        assertThat(fail.code()).isEqualTo("workflow_call_child_failed");
        assertThat(fail.message()).isNotBlank();
    }

    @Test
    void aCancelledChildFailsTheStepAsCancelled() {
        WorkflowCallRules.Outcome outcome = WorkflowCallRules.outcome(RunStatus.CANCELLED, null, List.of());

        assertThat(outcome).isInstanceOf(WorkflowCallRules.Fail.class);
        assertThat(((WorkflowCallRules.Fail) outcome).code()).isEqualTo("workflow_call_child_cancelled");
    }

    @Test
    void aChildThatIsNotFinishedChangesNothing() {
        for (RunStatus status : List.of(RunStatus.RUNNING, RunStatus.CANCELLING, RunStatus.WAITING_APPROVAL)) {
            assertThat(WorkflowCallRules.outcome(status, null, List.of()))
                    .isEqualTo(new WorkflowCallRules.NotFinished());
        }
    }

    private static RunStep step(int order, String output) {
        RunStep step = new RunStep(UUID.randomUUID(), UUID.randomUUID(), (short) order, "echo");
        step.markRunning(java.time.Instant.EPOCH);
        step.markCompleted("in", output, java.time.Instant.EPOCH);
        return step;
    }
}
