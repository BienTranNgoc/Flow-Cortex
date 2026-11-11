package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.workflow.api.NewWorkflowStep;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** {@link StepConfigRules} and its use by {@link KnownStepTypes}. */
class StepConfigRulesTest {

    private static final String GOOD =
            "{\"connector\":\"notion-main\",\"url\":\"https://api.notion.com/v1/users\"}";
    private static final String BAD = "{\"url\":\"https://api.notion.com/v1/users\"}";

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void typeWithoutRuleHasNoFault() {
        assertThat(StepConfigRules.fault("echo", null)).isNull();
        assertThat(StepConfigRules.fault("echo", "not json")).isNull();
        assertThat(StepConfigRules.fault(null, "not json")).isNull();
    }

    @Test
    void connectorCallConfigIsChecked() {
        assertThat(StepConfigRules.fault("connector.call", GOOD)).isNull();
        assertThat(StepConfigRules.fault("connector.call", BAD)).isEqualTo(ConnectorCallConfig.fault(BAD)).isNotNull();
        assertThat(StepConfigRules.fault("connector.call", null)).isNotNull();
    }

    @Test
    void badConnectorCallConfigIsOneViolationAtItsStep() {
        Set<ConstraintViolation<KnownStepTypes.Definition>> violations = validator.validate(definition(
                new NewWorkflowStep("a", "echo", null), new NewWorkflowStep("b", "connector.call", null, BAD)));

        assertThat(violations).hasSize(1);
        ConstraintViolation<KnownStepTypes.Definition> violation = violations.iterator().next();
        assertThat(violation.getPropertyPath().toString()).isEqualTo("steps[1].config");
        assertThat(violation.getMessage()).isEqualTo(ConnectorCallConfig.fault(BAD));
    }

    @Test
    void goodConnectorCallConfigPasses() {
        assertThat(validator.validate(definition(new NewWorkflowStep("b", "connector.call", null, GOOD)))).isEmpty();
    }

    @Test
    void inlineConnectorCallDeclaringOutputInputsReportsTheOutputInputsFault() {
        String config = "{\"connector\":\"notion-main\",\"url\":\"https://api.notion.com/v1/users\","
                + "\"outputInputs\":[{\"stepOrder\":1,\"outputName\":\"x\",\"inputKey\":\"y\"}]}";

        Set<ConstraintViolation<KnownStepTypes.Definition>> violations = validator.validate(definition(
                new NewWorkflowStep("a", "echo", null), new NewWorkflowStep("b", "connector.call", null, config)));

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage())
                .isEqualTo("only a remote step reads outputInputs; 'connector.call' runs inline");
    }

    private static KnownStepTypes.Definition definition(NewWorkflowStep... steps) {
        return new KnownStepTypes.Definition(List.of(steps), Set.of("echo", "connector.call"), Set.of(), Set.of());
    }
}
