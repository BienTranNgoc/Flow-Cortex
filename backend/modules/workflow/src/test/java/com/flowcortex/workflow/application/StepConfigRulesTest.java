package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.workflow.api.NewWorkflowStep;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StepConfigRulesTest {

    @Test
    void aTypeWithNoRuleHasNoFault() {
        assertThat(StepConfigRules.fault("echo", "{}")).isNull();
        assertThat(StepConfigRules.fault("echo", null)).isNull();
    }

    @Test
    void aWorkflowCallStepWithoutAWorkflowIdHasItsFault() {
        assertThat(StepConfigRules.fault("workflow.call", "{}")).isNotNull()
                .isEqualTo(WorkflowCallConfig.fault("{}"));
    }

    @Test
    void theValidatorReportsTheFaultAtTheStepsConfig() {
        List<NewWorkflowStep> steps = List.of(
                new NewWorkflowStep("a", "echo", null),
                new NewWorkflowStep("b", "workflow.call", null, "{\"workflowId\":\"abc\"}"));
        var definition = new KnownStepTypes.Definition(steps, Set.of("echo", "workflow.call"), Set.of(), Set.of());

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Set<ConstraintViolation<KnownStepTypes.Definition>> violations =
                    factory.getValidator().validate(definition);

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("steps[1].config");
        }
    }
}
