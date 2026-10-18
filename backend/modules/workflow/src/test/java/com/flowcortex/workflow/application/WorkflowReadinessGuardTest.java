package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.shared.web.UnprocessableEntityException;
import com.flowcortex.workflow.api.RunStartGuard.StartContext;
import com.flowcortex.workflow.api.RunStartGuard.StepDef;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.annotation.Order;

class WorkflowReadinessGuardTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID WORKFLOW = UUID.randomUUID();
    private static final StartContext CONTEXT = new StartContext(false);
    private static final StepDef FIRST = new StepDef(1, "Say hello", "echo", null, null);

    private final WorkflowReadinessGuard guard = new WorkflowReadinessGuard();

    private static StepDef second(String config, String inputRef) {
        return new StepDef(2, "Shout", "echo", config, inputRef);
    }

    static Stream<Arguments> incompleteSteps() {
        return Stream.of(
                Arguments.of("config is a JSON array", second("[1,2]", null), "Configuration is not a JSON object"),
                Arguments.of("config is a JSON string", second("\"text\"", null), "Configuration is not a JSON object"),
                Arguments.of("config is not JSON", second("{not json", null), "Configuration is not a JSON object"),
                Arguments.of("profile is blank", second("{\"profile\":\"  \"}", null), "Profile is empty"),
                Arguments.of("profile is a number", second("{\"profile\":7}", null), "Profile is empty"),
                Arguments.of("profile is null", second("{\"profile\":null}", null), "Profile is empty"),
                Arguments.of("reads from a later step", second(null, "{step:5.output}"),
                        "Reads from step 5, which is not an earlier step"),
                Arguments.of("reads from itself", second(null, "{step:2.value}"),
                        "Reads from step 2, which is not an earlier step"),
                Arguments.of("reads from step 0", second(null, "{step:0.value}"),
                        "Reads from step 0, which is not an earlier step"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("incompleteSteps")
    void anIncompleteStepRefusesTheStartWithItsOrderAndReason(String name, StepDef step, String reason) {
        assertThatThrownBy(() -> guard.check(OWNER, WORKFLOW, List.of(FIRST, step), CONTEXT))
                .isInstanceOfSatisfying(UnprocessableEntityException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("WORKFLOW_INCOMPLETE");
                    assertThat(e.getMessage())
                            .isEqualTo("This workflow can't run yet: step 2 is not fully configured.");
                    assertThat(e.getErrors()).containsExactly(Map.entry("step2", reason));
                });
    }

    @Test
    void everyFailingStepIsListedAndTheDetailNamesTheFirst() {
        List<StepDef> steps = List.of(
                FIRST,
                new StepDef(2, "Render", "render_video", "{\"profile\":\"\"}", null),
                new StepDef(3, "Fine", "echo", "{\"profile\":\"vertical_ad_v1\"}", "{step:2.output}"),
                new StepDef(4, "Ahead", "echo", null, "{step:9.field}"));

        assertThatThrownBy(() -> guard.check(OWNER, WORKFLOW, steps, CONTEXT))
                .isInstanceOfSatisfying(UnprocessableEntityException.class, e -> {
                    assertThat(e.getMessage())
                            .isEqualTo("This workflow can't run yet: step 2 is not fully configured.");
                    assertThat(e.getErrors()).containsOnly(
                            Map.entry("step2", "Profile is empty"),
                            Map.entry("step4", "Reads from step 9, which is not an earlier step"));
                });
    }

    @Test
    void aStepOneReferenceIsRefusedBecauseNoStepIsEarlier() {
        assertThatThrownBy(() -> guard.check(OWNER, WORKFLOW,
                List.of(new StepDef(1, "First", "echo", null, "{step:1.value}")), CONTEXT))
                .isInstanceOfSatisfying(UnprocessableEntityException.class, e -> assertThat(e.getErrors())
                        .containsExactly(Map.entry("step1", "Reads from step 1, which is not an earlier step")));
    }

    @Test
    void aCompleteWorkflowStarts() {
        List<StepDef> steps = List.of(
                FIRST,
                new StepDef(2, "Render", "render_video", "{\"profile\":\"vertical_ad_v1\"}", "{step:1.value}"),
                new StepDef(3, "Echo", "echo", "{}", null),
                new StepDef(4, "Blank config", "echo", "  ", null));

        assertThatCode(() -> guard.check(OWNER, WORKFLOW, steps, CONTEXT)).doesNotThrowAnyException();
        assertThatCode(() -> guard.check(OWNER, WORKFLOW, List.of(), CONTEXT)).doesNotThrowAnyException();
    }

    @Test
    void itRunsFirstAmongTheStartGuards() {
        assertThat(WorkflowReadinessGuard.class.getAnnotation(Order.class).value()).isEqualTo(1);
    }
}
