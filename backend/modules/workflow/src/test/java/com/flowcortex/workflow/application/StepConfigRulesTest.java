package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.workflow.api.IterateStepConfig;
import com.flowcortex.workflow.api.NewWorkflowStep;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
        var definition = new KnownStepTypes.Definition(steps, Set.of("echo", "workflow.call"), Set.of(), Set.of(),
                Set.of());

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Set<ConstraintViolation<KnownStepTypes.Definition>> violations =
                    factory.getValidator().validate(definition);

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("steps[1].config");
        }
    }

    @Test
    void iterateConfigFaultReported() {
        assertThat(StepConfigRules.fault("iterate", "{}")).isNotNull()
                .isEqualTo(IterateStepConfig.fault("{}"));
        assertThat(StepConfigRules.fault("iterate", "{\"child\":{\"type\":\"echo\"}}")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"workflow.call", "iterate", "APPROVAL_CHECKPOINT", "probe", "generate_video", "nope",
            "inline.extra"})
    void iterateChildTypeOutsideTheListIsOneViolationAtConfig(String childType) {
        Set<ConstraintViolation<KnownStepTypes.Definition>> violations = validate(iterateStep(childType));

        assertThat(violations).hasSize(1);
        ConstraintViolation<KnownStepTypes.Definition> violation = violations.iterator().next();
        assertThat(violation.getPropertyPath().toString()).isEqualTo("steps[0].config");
        assertThat(violation.getMessage()).contains("child.type").contains("echo, fail, translate, tts");
    }

    @Test
    void theChildTypeRefusalDoesNotEchoTheTypeTheCallerSent() {
        Set<ConstraintViolation<KnownStepTypes.Definition>> violations = validate(iterateStep("zzz-secret-type"));

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage()).doesNotContain("zzz-secret-type");
    }

    @Test
    void iterateWithEchoChildIsValid() {
        assertThat(validate(iterateStep("echo"))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"fail", "translate", "tts"})
    void iterateWithEveryOtherListedChildIsValid(String childType) {
        assertThat(validate(iterateStep(childType))).isEmpty();
    }

    @Test
    void anIterateConfigFaultWinsOverTheChildTypeCheck() {
        NewWorkflowStep step = new NewWorkflowStep("a", "iterate", null,
                "{\"child\":{\"type\":\"nope\"},\"maxItems\":51}");

        Set<ConstraintViolation<KnownStepTypes.Definition>> violations = validate(step);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage()).contains("maxItems");
    }

    private static NewWorkflowStep iterateStep(String childType) {
        return new NewWorkflowStep("a", "iterate", null, "{\"child\":{\"type\":\"" + childType + "\"}}");
    }

    private static Set<ConstraintViolation<KnownStepTypes.Definition>> validate(NewWorkflowStep step) {
        var definition = new KnownStepTypes.Definition(List.of(step),
                Set.of("echo", "fail", "translate", "tts", "iterate", "workflow.call", "probe", "generate_video"),
                Set.of(), Set.of("probe", "generate_video"), Set.of("echo", "fail", "translate", "tts"));
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            return factory.getValidator().validate(definition);
        }
    }
}
