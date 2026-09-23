package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Input resolution: step 1 resolves to the run's own input; step N defaults
 * to the previous step's output, or an explicit {@code {step:K.field}}
 * reference; an unresolved/incomplete reference throws rather than
 * resolving to an empty string.
 */
class InputResolverTest {

    private final InputResolver resolver = new InputResolver();

    @Test
    void stepOneAlwaysResolvesToTheRunsOwnInput() {
        String resolved = resolver.resolve(1, null, "the run input", Map.of());

        assertThat(resolved).isEqualTo("the run input");
    }

    @Test
    void stepOneIgnoresAnyInputRefAndStillUsesRunInput() {
        // step 1 has no "previous step" to default from — step 1 <-
        // run.input is fixed, unconditionally.
        String resolved = resolver.resolve(1, "{step:1.field}", "the run input", Map.of());

        assertThat(resolved).isEqualTo("the run input");
    }

    @Test
    void stepNDefaultsToThePreviousStepsOutputWhenNoInputRefIsSet() {
        Map<Integer, String> priorOutputs = new LinkedHashMap<>();
        priorOutputs.put(1, "step-1-output");

        String resolved = resolver.resolve(2, null, "the run input", priorOutputs);

        assertThat(resolved).isEqualTo("step-1-output");
    }

    @Test
    void stepNDefaultingWithNoPriorOutputThrows() {
        assertThatThrownBy(() -> resolver.resolve(2, null, "the run input", Map.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void stepNDefaultsToNullWhenThePreviousStepCompletedWithNoOutput() {
        // The bug the browser-loop QA caught: a step that ACTUALLY completed
        // (e.g. an "echo" step given the run's own null/empty input) records
        // a null output in priorOutputs — that is a completed step's real
        // output, not an absent one, and must default through rather than
        // erroring (resolution only errors on a step that never completed).
        Map<Integer, String> priorOutputs = new LinkedHashMap<>();
        priorOutputs.put(1, null);

        String resolved = resolver.resolve(2, null, "the run input", priorOutputs);

        assertThat(resolved).isNull();
    }

    @Test
    void inputRefResolvesANamedFieldFromAnEarlierStepsJsonOutput() {
        Map<Integer, String> priorOutputs = new LinkedHashMap<>();
        priorOutputs.put(1, "{\"result\":\"forty-two\"}");

        String resolved = resolver.resolve(2, "{step:1.result}", "the run input", priorOutputs);

        assertThat(resolved).isEqualTo("forty-two");
    }

    @Test
    void malformedInputRefThrows() {
        assertThatThrownBy(() -> resolver.resolve(2, "not-a-valid-ref", "the run input", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid input_ref");
    }

    @Test
    void inputRefToAStepWithNoOutputYetThrows() {
        assertThatThrownBy(() -> resolver.resolve(3, "{step:2.field}", "the run input", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no output available");
    }

    @Test
    void inputRefToNonJsonOutputThrows() {
        Map<Integer, String> priorOutputs = new LinkedHashMap<>();
        priorOutputs.put(1, "not json at all");

        assertThatThrownBy(() -> resolver.resolve(2, "{step:1.field}", "the run input", priorOutputs))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not valid JSON");
    }

    @Test
    void inputRefToAMissingFieldThrows() {
        Map<Integer, String> priorOutputs = new LinkedHashMap<>();
        priorOutputs.put(1, "{\"other\":\"value\"}");

        assertThatThrownBy(() -> resolver.resolve(2, "{step:1.missing}", "the run input", priorOutputs))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not found");
    }
}
