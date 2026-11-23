package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.workflow.api.NewWorkflowStep;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class KnownStepTypesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private List<String> violationsOf(NewWorkflowStep... steps) {
        Set<ConstraintViolation<KnownStepTypes.Definition>> violations = validator.validate(
                new KnownStepTypes.Definition(List.of(steps), Set.of("echo"), Set.of(), Set.of(), Set.of()));
        return violations.stream()
                .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                .sorted()
                .toList();
    }

    private static NewWorkflowStep review(String config) {
        return new NewWorkflowStep("review", "qa.review", null, config);
    }

    @Test
    void qaReviewWithoutConfigOrWithInstructionIsValid() {
        assertThat(violationsOf(review(null))).isEmpty();
        assertThat(violationsOf(review("{}"))).isEmpty();
        assertThat(violationsOf(review("{\"instruction\":\"Ask about tone and audience\"}"))).isEmpty();
        assertThat(violationsOf(review("{\"instruction\":\"" + "a".repeat(2000) + "\"}"))).isEmpty();
    }

    @Test
    void qaReviewWithAnotherKeyIsRefusedAtStepsConfig() {
        assertThat(violationsOf(new NewWorkflowStep("first", "echo", null), review("{\"model\":\"x\"}")))
                .containsExactly("steps[1].config qa.review config takes only an instruction; found 'model'");
        assertThat(violationsOf(review("{\"instruction\":7}")))
                .containsExactly("steps[0].config qa.review instruction must be text");
    }

    @Test
    void instructionOver2000IsRefused() {
        assertThat(violationsOf(review("{\"instruction\":\"" + "a".repeat(2001) + "\"}")))
                .containsExactly("steps[0].config qa.review instruction is over 2000 characters");
    }

    @Test
    void qaReviewWithOutputInputsIsRefused() {
        String config = "{\"outputInputs\":[{\"stepOrder\":1,\"outputName\":\"a.wav\",\"inputKey\":\"a\"}]}";

        assertThat(violationsOf(new NewWorkflowStep("first", "echo", null), review(config)))
                .containsExactly("steps[1].config only a remote step reads outputInputs; 'qa.review' runs inline");
    }

    @Test
    void qaReviewWithAConfigThatIsNotAJsonObjectIsRefused() {
        assertThat(violationsOf(review("[1]"))).containsExactly("steps[0].config config is not a JSON object");
        assertThat(violationsOf(review("{not json"))).containsExactly("steps[0].config config is not JSON");
    }
}
