package com.flowcortex.llm.descriptor;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.llm.api.ModelDescriptor;
import com.flowcortex.llm.api.ModelDescriptor.Knob;
import com.flowcortex.llm.api.ModelDescriptor.Limits;
import com.flowcortex.llm.api.ModelDescriptor.Modality;
import com.flowcortex.llm.api.ModelDescriptor.Status;
import com.flowcortex.llm.api.ModelDescriptor.StructuredOutput;
import com.flowcortex.llm.api.ModelDescriptor.Thinking;
import java.util.List;
import org.junit.jupiter.api.Test;

class DescriptorRulesTest {

    private static final Thinking LEVELS = new Thinking(Knob.LEVEL, List.of("low", "medium", "high"), "medium");

    private static ModelDescriptor descriptor(
            String id, List<Modality> modalities, Limits limits, Thinking thinking, Status status, String successor) {
        return new ModelDescriptor(
                id, "gemini", "flash", modalities, limits, true, StructuredOutput.RESPONSE_SCHEMA, thinking, true,
                status, null, successor, null);
    }

    private static ModelDescriptor valid(String id) {
        return descriptor(id, List.of(Modality.TEXT, Modality.IMAGE), new Limits(8192, 4096), LEVELS, Status.STABLE, null);
    }

    private static ModelDescriptor deprecated(String id, String successor) {
        return descriptor(
                id, List.of(Modality.TEXT), new Limits(8192, 4096), LEVELS, Status.DEPRECATED, successor);
    }

    @Test
    void aValidSetHasNoViolation() {
        assertThat(DescriptorRules.violations(List.of(valid("a"), valid("b"), deprecated("old", "a")))).isEmpty();
    }

    @Test
    void anEmptySetHasNoViolation() {
        assertThat(DescriptorRules.violations(List.of())).isEmpty();
    }

    @Test
    void aDuplicateIdIsNamedOnce() {
        List<String> violations = DescriptorRules.violations(List.of(valid("gemini-x"), valid("gemini-x"), valid("b")));

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).contains("gemini-x");
    }

    @Test
    void aThinkingDefaultOutsideItsValuesIsNamed() {
        Thinking bad = new Thinking(Knob.LEVEL, List.of("low", "medium", "high"), "max");
        ModelDescriptor model = descriptor(
                "lvl", List.of(Modality.TEXT), new Limits(8192, 4096), bad, Status.STABLE, null);

        List<String> violations = DescriptorRules.violations(List.of(model, valid("ok")));

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).contains("lvl").doesNotContain("ok");
    }

    @Test
    void aThinkingKnobWithoutADefaultIsNamed() {
        Thinking bad = new Thinking(Knob.BUDGET, List.of("0", "24576"), null);
        ModelDescriptor model = descriptor(
                "bud", List.of(Modality.TEXT), new Limits(8192, 4096), bad, Status.STABLE, null);

        assertThat(DescriptorRules.violations(List.of(model))).singleElement().asString().contains("bud");
    }

    @Test
    void aNoneKnobWithValuesIsNamed() {
        Thinking bad = new Thinking(Knob.NONE, List.of("low"), null);
        ModelDescriptor model = descriptor(
                "none-values", List.of(Modality.TEXT), new Limits(8192, 4096), bad, Status.STABLE, null);

        assertThat(DescriptorRules.violations(List.of(model))).singleElement().asString().contains("none-values");
    }

    @Test
    void aNoneKnobWithADefaultIsNamed() {
        Thinking bad = new Thinking(Knob.NONE, List.of(), "low");
        ModelDescriptor model = descriptor(
                "none-default", List.of(Modality.TEXT), new Limits(8192, 4096), bad, Status.STABLE, null);

        assertThat(DescriptorRules.violations(List.of(model))).singleElement().asString().contains("none-default");
    }

    @Test
    void aNoneKnobWithNothingIsValid() {
        Thinking none = new Thinking(Knob.NONE, List.of(), null);
        ModelDescriptor model = descriptor(
                "plain", List.of(Modality.TEXT), new Limits(8192, 4096), none, Status.STABLE, null);

        assertThat(DescriptorRules.violations(List.of(model))).isEmpty();
    }

    @Test
    void anOutputLimitAboveTheContextLimitIsNamed() {
        ModelDescriptor model = descriptor(
                "wide", List.of(Modality.TEXT), new Limits(8192, 16384), LEVELS, Status.STABLE, null);

        assertThat(DescriptorRules.violations(List.of(model))).singleElement().asString().contains("wide");
    }

    @Test
    void anOutputLimitEqualToTheContextLimitIsValid() {
        ModelDescriptor model = descriptor(
                "tight", List.of(Modality.TEXT), new Limits(8192, 8192), LEVELS, Status.STABLE, null);

        assertThat(DescriptorRules.violations(List.of(model))).isEmpty();
    }

    @Test
    void modalitiesWithoutTextAreNamed() {
        ModelDescriptor model = descriptor(
                "pictures", List.of(Modality.IMAGE), new Limits(8192, 4096), LEVELS, Status.STABLE, null);

        assertThat(DescriptorRules.violations(List.of(model))).singleElement().asString().contains("pictures");
    }

    @Test
    void aDeprecatedDescriptorWithoutASuccessorIsNamed() {
        assertThat(DescriptorRules.violations(List.of(deprecated("old", null))))
                .singleElement().asString().contains("old");
    }

    @Test
    void aSuccessorWithoutADescriptorIsNamed() {
        assertThat(DescriptorRules.violations(List.of(deprecated("old", "ghost"))))
                .singleElement().asString().contains("old").contains("ghost");
    }

    @Test
    void aDeprecatedSuccessorIsNamedForEachDescriptorPointingAtIt() {
        List<ModelDescriptor> set = List.of(deprecated("first", "second"), deprecated("second", "third"), valid("third"));

        List<String> violations = DescriptorRules.violations(set);

        assertThat(violations).singleElement().asString().contains("first").contains("second");
    }

    @Test
    void aDeprecatedDescriptorPointingAtItselfIsNamed() {
        assertThat(DescriptorRules.violations(List.of(deprecated("loop", "loop"))))
                .singleElement().asString().contains("loop");
    }

    @Test
    void everyBrokenIdIsReported() {
        List<ModelDescriptor> set = List.of(deprecated("old", null), deprecated("older", "ghost"));

        List<String> violations = DescriptorRules.violations(set);

        assertThat(violations).hasSize(2);
        assertThat(violations.get(0)).contains("old");
        assertThat(violations.get(1)).contains("older");
    }
}
