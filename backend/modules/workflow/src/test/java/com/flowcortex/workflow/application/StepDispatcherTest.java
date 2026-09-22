package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Task 5.3 — the two MVP step types (design.md D1 Open Questions, task 5.9). */
class StepDispatcherTest {

    private final StepDispatcher dispatcher = new StepDispatcher();

    @Test
    void echoReturnsItsResolvedInputVerbatim() {
        assertThat(dispatcher.execute("echo", "hello world")).isEqualTo("hello world");
    }

    @Test
    void echoWithNullInputReturnsNull() {
        assertThat(dispatcher.execute("echo", null)).isNull();
    }

    @Test
    void failAlwaysThrowsDeterministically() {
        assertThatThrownBy(() -> dispatcher.execute("fail", "anything"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Simulated step failure");
    }

    @Test
    void unknownTypeThrows() {
        assertThatThrownBy(() -> dispatcher.execute("unknown-type", "anything"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsupported step type");
    }
}
