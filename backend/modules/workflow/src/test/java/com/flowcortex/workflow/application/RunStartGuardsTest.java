package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.credit.api.CreditAccounts;
import com.flowcortex.credit.api.InsufficientCreditsException;
import com.flowcortex.media.api.MediaJobs;
import com.flowcortex.workflow.api.RunStartGuard;
import com.flowcortex.workflow.api.RunStartGuard.StartContext;
import com.flowcortex.workflow.api.RunStartGuard.StepDef;
import com.flowcortex.workflow.domain.WorkflowStep;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

    private static final Map<String, Long> PRICES = Map.of("echo", 1L, "translate", 100L, "iterate", 0L);
    private static final List<StepDef> ITERATE_WORKFLOW = List.of(
            new StepDef(1, "say", "echo", null, null),
            new StepDef(2, "each", "iterate", "{\"child\":{\"type\":\"translate\"},\"maxItems\":10}", null));

    private final CreditAccounts credits = mock(CreditAccounts.class);

    private CreditRunStartGuard creditGuard(long balance) {
        when(credits.enabled()).thenReturn(true);
        when(credits.balance(OWNER)).thenReturn(balance);
        when(credits.estimate(anyList())).thenAnswer(invocation -> invocation.<List<String>>getArgument(0).stream()
                .mapToLong(PRICES::get).sum());
        return new CreditRunStartGuard(credits, new StepExecutorRegistry(mock(MediaJobs.class), Clock.systemUTC()));
    }

    @Test
    void iterateIsEstimatedAsMaxItemsChildren() {
        CreditRunStartGuard guard = creditGuard(5000);

        guard.check(OWNER, WORKFLOW, ITERATE_WORKFLOW, new StartContext(false));

        ArgumentCaptor<List<String>> types = ArgumentCaptor.captor();
        verify(credits).estimate(types.capture());
        List<String> expected = new ArrayList<>(List.of("echo", "iterate"));
        expected.addAll(Collections.nCopies(10, "translate"));
        assertThat(types.getValue()).isEqualTo(expected);
    }

    @Test
    void startBelowTheUpperBoundIsRefusedUnlessAcknowledged() {
        CreditRunStartGuard guard = creditGuard(500);

        assertThatThrownBy(() -> guard.check(OWNER, WORKFLOW, ITERATE_WORKFLOW, new StartContext(false)))
                .isInstanceOfSatisfying(InsufficientCreditsException.class, e -> {
                    assertThat(e.required()).isEqualTo(1001);
                    assertThat(e.balance()).isEqualTo(500);
                });

        guard.check(OWNER, WORKFLOW, ITERATE_WORKFLOW, new StartContext(true));
    }

    @Test
    void aStepWithAnUnreadableIterateConfigIsEstimatedAsTheTypeAlone() {
        CreditRunStartGuard guard = creditGuard(5000);

        guard.check(OWNER, WORKFLOW, List.of(new StepDef(1, "each", "iterate", "{", null)),
                new StartContext(false));

        ArgumentCaptor<List<String>> types = ArgumentCaptor.captor();
        verify(credits).estimate(types.capture());
        assertThat(types.getValue()).containsExactly("iterate");
    }
}
