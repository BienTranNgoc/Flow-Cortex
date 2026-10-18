package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.workflow.api.RunStartGuard;
import com.flowcortex.workflow.api.RunStartGuard.StartContext;
import com.flowcortex.workflow.api.RunStartGuard.StepDef;
import com.flowcortex.workflow.domain.WorkflowStep;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.Order;

class RunStartGuardsTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID WORKFLOW = UUID.randomUUID();
    private static final List<WorkflowStep> STEPS = List.of(
            new WorkflowStep(UUID.randomUUID(), WORKFLOW, (short) 1, "Say hello", "echo", null),
            new WorkflowStep(UUID.randomUUID(), WORKFLOW, (short) 2, "Shout", "echo", "{step:1.value}"));

    private final List<String> calls = new ArrayList<>();

    @Order(1)
    private final class First implements RunStartGuard {
        private final boolean refuse;

        First(boolean refuse) {
            this.refuse = refuse;
        }

        @Override
        public void check(UUID ownerId, UUID workflowId, List<StepDef> steps, StartContext context) {
            calls.add("first " + ownerId.equals(OWNER) + " " + workflowId.equals(WORKFLOW) + " " + steps + " "
                    + context.acknowledgeCost());
            if (refuse) {
                throw new IllegalStateException("refused by first");
            }
        }
    }

    @Order(2)
    private final class Second implements RunStartGuard {
        @Override
        public void check(UUID ownerId, UUID workflowId, List<StepDef> steps, StartContext context) {
            calls.add("second");
        }
    }

    @Test
    void guardsRunInOrderAndTheFirstRefusalStopsTheRest() {
        RunStartGuards passing = new RunStartGuards(List.of(new Second(), new First(false)));
        passing.check(OWNER, WORKFLOW, STEPS, new StartContext(true));

        assertThat(calls).containsExactly(
                "first true true [StepDef[order=1, name=Say hello, type=echo, config=null, inputRef=null], "
                        + "StepDef[order=2, name=Shout, type=echo, config=null, inputRef={step:1.value}]] true",
                "second");

        calls.clear();
        RunStartGuards refusing = new RunStartGuards(List.of(new Second(), new First(true)));

        assertThatThrownBy(() -> refusing.check(OWNER, WORKFLOW, STEPS, new StartContext(false)))
                .hasMessage("refused by first");
        assertThat(calls).hasSize(1).first().asString().startsWith("first");
    }
}
