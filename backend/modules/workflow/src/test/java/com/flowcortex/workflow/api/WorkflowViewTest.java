package com.flowcortex.workflow.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.credit.api.CreditAccounts;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WorkflowViewTest {

    private final CreditAccounts credits = mock(CreditAccounts.class);

    private static WorkflowView view(WorkflowStepView... steps) {
        return new WorkflowView(UUID.randomUUID(), UUID.randomUUID(), "Demo", WorkflowVisibility.PRIVATE,
                Instant.EPOCH, Instant.EPOCH, List.of(steps), true, "draft", null, null);
    }

    private static WorkflowStepView step(int order, String type, String config) {
        return new WorkflowStepView(UUID.randomUUID(), order, "s" + order, type, "inline", null, config);
    }

    @Test
    void anIterateStepIsEstimatedAsItsMaxItemsChildren() {
        when(credits.enabled()).thenReturn(true);
        when(credits.estimate(anyList())).thenReturn(1001L);
        WorkflowView view = view(step(1, "echo", null),
                step(2, "iterate", "{\"child\":{\"type\":\"translate\"},\"maxItems\":10}"));

        assertThat(view.estimatedCredits(credits)).isEqualTo(1001L);

        ArgumentCaptor<List<String>> types = ArgumentCaptor.captor();
        verify(credits).estimate(types.capture());
        List<String> expected = new ArrayList<>(List.of("echo", "iterate"));
        expected.addAll(Collections.nCopies(10, "translate"));
        assertThat(types.getValue()).isEqualTo(expected);
    }

    @Test
    void anIterateStepWhoseConfigIsHiddenIsEstimatedAsTheTypeAlone() {
        when(credits.enabled()).thenReturn(true);
        WorkflowView view = view(step(1, "iterate", null));

        view.estimatedCredits(credits);

        ArgumentCaptor<List<String>> types = ArgumentCaptor.captor();
        verify(credits).estimate(types.capture());
        assertThat(types.getValue()).containsExactly("iterate");
    }
}
