package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The two MVP step types. */
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
