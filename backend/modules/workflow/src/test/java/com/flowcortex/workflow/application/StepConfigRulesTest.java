package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.workflow.api.IterateStepConfig;
import com.flowcortex.workflow.api.NewWorkflowStep;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
    void aWorkflowCallStepWithoutAWorkflowIdHasItsFault() {
        assertThat(StepConfigRules.fault("workflow.call", "{}")).isNotNull()
                .isEqualTo(WorkflowCallConfig.fault("{}"));
    }

    @Test
    void theValidatorReportsAWorkflowCallFaultAtTheStepsConfig() {
        Set<ConstraintViolation<KnownStepTypes.Definition>> violations = validator.validate(definition(
                new NewWorkflowStep("a", "echo", null),
                new NewWorkflowStep("b", "workflow.call", null, "{\"workflowId\":\"abc\"}")));

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("steps[1].config");
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

    @Test
    void driveReadConfigIsChecked() {
        assertThat(StepConfigRules.fault("drive.read", "{\"folderFromInput\":true}")).isNull();
        assertThat(StepConfigRules.fault("drive.read", "{}")).isEqualTo(DriveReadConfig.fault("{}")).isNotNull();
        assertThat(StepConfigRules.fault("drive.read", null)).isNotNull();
    }

    @Test
    void badDriveReadConfigIsOneViolationAtItsStep() {
        Set<ConstraintViolation<KnownStepTypes.Definition>> violations = validator.validate(definition(
                new NewWorkflowStep("a", "echo", null), new NewWorkflowStep("b", "drive.read", null, "{}")));

        assertThat(violations).hasSize(1);
        ConstraintViolation<KnownStepTypes.Definition> violation = violations.iterator().next();
        assertThat(violation.getPropertyPath().toString()).isEqualTo("steps[1].config");
        assertThat(violation.getMessage()).isEqualTo(DriveReadConfig.fault("{}"));
    }

    private static KnownStepTypes.Definition definition(NewWorkflowStep... steps) {
        return new KnownStepTypes.Definition(List.of(steps),
                Set.of("echo", "connector.call", "workflow.call", "drive.read"),
                Set.of(), Set.of(), Set.of(), Set.of());
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

    private Set<ConstraintViolation<KnownStepTypes.Definition>> validate(NewWorkflowStep step) {
        var definition = new KnownStepTypes.Definition(List.of(step),
                Set.of("echo", "fail", "translate", "tts", "iterate", "workflow.call", "probe", "generate_video"),
                Set.of(), Set.of("probe", "generate_video"), Set.of(), Set.of("echo", "fail", "translate", "tts"));
        return validator.validate(definition);
    }
}
