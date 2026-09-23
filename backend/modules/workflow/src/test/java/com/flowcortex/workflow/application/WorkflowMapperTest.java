package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.workflow.api.RunView;
import com.flowcortex.workflow.api.WorkflowView;
import com.flowcortex.workflow.domain.RunStep;
import com.flowcortex.workflow.domain.RunStepStatus;
import com.flowcortex.workflow.domain.Workflow;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.domain.WorkflowStep;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Entity -&gt; {@code api} view mapping, including the lowercase wire-status rule. */
class WorkflowMapperTest {

    @Test
    void toWorkflowViewMapsStepsInOrder() {
        Workflow workflow = new Workflow(UUID.randomUUID(), UUID.randomUUID(), "Demo", true);
        WorkflowStep step = new WorkflowStep(UUID.randomUUID(), workflow.getId(), (short) 1, "First", "echo", null);

        WorkflowView view = WorkflowMapper.toWorkflowView(workflow, List.of(step));

        assertThat(view.id()).isEqualTo(workflow.getId());
        assertThat(view.shared()).isTrue();
        assertThat(view.steps()).hasSize(1);
        assertThat(view.steps().get(0).type()).isEqualTo("echo");
    }

    @Test
    void toRunViewLowercasesTheStatusForTheWire() {
        WorkflowRun run = new WorkflowRun(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "in",
                Instant.now());
        RunStep step = new RunStep(UUID.randomUUID(), run.getId(), (short) 1, "echo");
        step.markRunning(Instant.now());
        step.markCompleted("in", "out", Instant.now());

        RunView view = WorkflowMapper.toRunView(run, List.of(step));

        assertThat(view.status()).isEqualTo("running");
        assertThat(view.steps().get(0).status()).isEqualTo("completed");
        assertThat(view.steps().get(0).output()).isEqualTo("out");
    }

    @Test
    void toRunViewReflectsErrorStatusLowercase() {
        WorkflowRun run = new WorkflowRun(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "in",
                Instant.now());
        run.markError("boom", Instant.now());

        RunView view = WorkflowMapper.toRunView(run, List.of());

        assertThat(view.status()).isEqualTo("error");
        assertThat(view.errorMessage()).isEqualTo("boom");
    }

    @Test
    void pendingRunStepStatusIsLowercase() {
        RunStep step = new RunStep(UUID.randomUUID(), UUID.randomUUID(), (short) 1, "echo");
        assertThat(step.getStatus()).isEqualTo(RunStepStatus.PENDING);

        WorkflowRun run = new WorkflowRun(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "in",
                Instant.now());
        RunView view = WorkflowMapper.toRunView(run, List.of(step));

        assertThat(view.steps().get(0).status()).isEqualTo("pending");
    }
}
